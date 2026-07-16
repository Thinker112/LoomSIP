package org.loomsip.transaction.noninvite;

import org.loomsip.codec.SipParseException;
import org.loomsip.message.SipMethod;
import org.loomsip.message.SipRequest;
import org.loomsip.message.SipResponse;
import org.loomsip.transaction.InMemoryTransactionRepository;
import org.loomsip.transaction.SipTransaction;
import org.loomsip.transaction.TransactionKey;
import org.loomsip.transaction.TransactionKeyException;
import org.loomsip.transaction.TransactionKeyFactory;
import org.loomsip.transaction.TransactionMessageSender;
import org.loomsip.transaction.TransportReliability;
import org.loomsip.transaction.event.RequestReceived;
import org.loomsip.transaction.event.ResponseReceived;
import org.loomsip.transaction.timer.DefaultSipScheduler;
import org.loomsip.transaction.timer.SipScheduler;
import org.loomsip.transaction.timer.SipTimerConfig;
import org.loomsip.transport.InboundSipMessage;
import org.loomsip.transport.SipMessageHandler;
import org.loomsip.transport.TransportContext;
import org.loomsip.transport.TransportEndpoint;

import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Dispatcher and lifecycle owner for RFC 3261 Non-INVITE transactions.
 *
 * <pre>{@code
 * InboundSipMessage
 *        |
 *        v
 * +-----------------------------+
 * | NonInviteTransactionManager |
 * | - derive TransactionKey     |
 * | - client/server repository  |
 * | - find or create            |
 * +------------+----------------+
 *              |
 *       +------+------+
 *       |             |
 *       v             v
 *     NICT           NIST
 *       |             |
 *       +------+------+
 *              v
 *      Transaction Mailbox
 * }</pre>
 */
public final class NonInviteTransactionManager implements SipMessageHandler, AutoCloseable {

    private static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(5);

    private final TransactionMessageSender sender;
    private final SipTimerConfig timerConfig;
    private final NonInviteTransactionConfig config;
    private final NonInviteClientListener clientListener;
    private final NonInviteServerListener serverListener;
    private final SipScheduler scheduler;
    private final Executor transactionExecutor;
    private final Executor callbackExecutor;
    private final ExecutorService ownedTransactionExecutor;
    private final ExecutorService ownedCallbackExecutor;
    private final boolean ownsScheduler;
    private final InMemoryTransactionRepository clientTransactions;
    private final InMemoryTransactionRepository serverTransactions;
    private final Set<NonInviteClientTransaction> activeClients = ConcurrentHashMap.newKeySet();
    private final Set<NonInviteServerTransaction> activeServers = ConcurrentHashMap.newKeySet();
    private final Object lifecycleMonitor = new Object();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final CompletableFuture<Void> activeTransactionsStopped = new CompletableFuture<>();

    /**
     * Creates a manager that owns its scheduler and virtual-thread executors.
     *
     * @param sender raw message sender, normally a SIP transport method reference
     * @param timerConfig SIP base timer configuration
     * @param config transaction and queue capacities
     * @param clientListener client TU notifications
     * @param serverListener server TU notifications
     */
    public NonInviteTransactionManager(
            TransactionMessageSender sender,
            SipTimerConfig timerConfig,
            NonInviteTransactionConfig config,
            NonInviteClientListener clientListener,
            NonInviteServerListener serverListener
    ) {
        this(
                sender,
                timerConfig,
                config,
                clientListener,
                serverListener,
                new DefaultSipScheduler(),
                newVirtualExecutor("loomsip-noninvite-tx-"),
                newVirtualExecutor("loomsip-noninvite-tu-"),
                true
        );
    }

    /**
     * Creates a manager using externally owned scheduling and execution resources.
     *
     * <p>This constructor is useful for deterministic tests. Closing the manager
     * terminates transactions but does not close the supplied scheduler or executors.</p>
     *
     * @param sender raw message sender
     * @param timerConfig SIP base timer configuration
     * @param config transaction and queue capacities
     * @param clientListener client TU notifications
     * @param serverListener server TU notifications
     * @param scheduler externally owned scheduler
     * @param transactionExecutor state-machine executor
     * @param callbackExecutor TU callback executor
     */
    public NonInviteTransactionManager(
            TransactionMessageSender sender,
            SipTimerConfig timerConfig,
            NonInviteTransactionConfig config,
            NonInviteClientListener clientListener,
            NonInviteServerListener serverListener,
            SipScheduler scheduler,
            Executor transactionExecutor,
            Executor callbackExecutor
    ) {
        this(
                sender,
                timerConfig,
                config,
                clientListener,
                serverListener,
                scheduler,
                transactionExecutor,
                callbackExecutor,
                false
        );
    }

    private NonInviteTransactionManager(
            TransactionMessageSender sender,
            SipTimerConfig timerConfig,
            NonInviteTransactionConfig config,
            NonInviteClientListener clientListener,
            NonInviteServerListener serverListener,
            SipScheduler scheduler,
            Executor transactionExecutor,
            Executor callbackExecutor,
            boolean ownsResources
    ) {
        this.sender = Objects.requireNonNull(sender, "sender");
        this.timerConfig = Objects.requireNonNull(timerConfig, "timerConfig");
        this.config = Objects.requireNonNull(config, "config");
        this.clientListener = Objects.requireNonNull(clientListener, "clientListener");
        this.serverListener = Objects.requireNonNull(serverListener, "serverListener");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.transactionExecutor = Objects.requireNonNull(transactionExecutor, "transactionExecutor");
        this.callbackExecutor = Objects.requireNonNull(callbackExecutor, "callbackExecutor");
        ownsScheduler = ownsResources;
        ownedTransactionExecutor = ownsResources ? (ExecutorService) transactionExecutor : null;
        ownedCallbackExecutor = ownsResources ? (ExecutorService) callbackExecutor : null;
        clientTransactions = new InMemoryTransactionRepository(config.clientTransactions());
        serverTransactions = new InMemoryTransactionRepository(config.serverTransactions());
    }

    /**
     * Registers and starts a Non-INVITE client transaction.
     *
     * <p>Registration happens before the first network write, preventing a fast
     * response from arriving before the client transaction is visible.</p>
     *
     * @param request non-INVITE request
     * @param target selected remote endpoint
     * @return client transaction handle
     * @throws TransactionKeyException if routing headers are malformed
     * @throws IllegalArgumentException for INVITE, ACK, or CANCEL in this milestone
     * @throws IllegalStateException if the manager is closed
     */
    public ClientTransactionHandle sendRequest(SipRequest request, TransportEndpoint target)
            throws TransactionKeyException {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(target, "target");
        rejectSpecialMethod(request.method());

        TransactionKey key = TransactionKeyFactory.fromRequest(request);
        NonInviteClientTransaction transaction = new NonInviteClientTransaction(
                key,
                request,
                target,
                TransportReliability.from(target.protocol()),
                sender,
                timerConfig,
                scheduler,
                transactionExecutor,
                callbackExecutor,
                config,
                clientListener,
                this::clientTerminated
        );
        synchronized (lifecycleMonitor) {
            ensureOpen();
            activeClients.add(transaction);
            try {
                clientTransactions.register(transaction);
                transaction.start();
                return transaction;
            } catch (RuntimeException exception) {
                activeClients.remove(transaction);
                clientTransactions.remove(key, transaction);
                transaction.shutdown();
                throw exception;
            }
        }
    }

    @Override
    public void onMessage(InboundSipMessage inbound) {
        Objects.requireNonNull(inbound, "inbound");
        if (closed.get()) {
            return;
        }
        if (inbound.message() instanceof SipRequest request) {
            dispatchRequest(request, inbound.context());
        } else {
            dispatchResponse((SipResponse) inbound.message(), inbound.context());
        }
    }

    @Override
    public void onMalformedMessage(TransportContext context, SipParseException cause) {
        reportLayerError(new NonInviteTransactionLayerException(
                "transport rejected malformed SIP message from " + context.remoteAddress(),
                cause
        ));
    }

    @Override
    public void onTransportError(Throwable cause) {
        reportLayerError(cause);
    }

    /**
     * Returns the active client transaction count.
     *
     * @return active NICT count
     */
    public int activeClientTransactions() {
        return clientTransactions.size();
    }

    /**
     * Returns the active server transaction count.
     *
     * @return active NIST count
     */
    public int activeServerTransactions() {
        return serverTransactions.size();
    }

    /**
     * Terminates active transactions and releases resources owned by this manager.
     */
    @Override
    public void close() {
        synchronized (lifecycleMonitor) {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            if (activeClients.isEmpty() && activeServers.isEmpty()) {
                activeTransactionsStopped.complete(null);
            } else {
                activeClients.forEach(NonInviteClientTransaction::shutdown);
                activeServers.forEach(NonInviteServerTransaction::shutdown);
            }
        }

        try {
            activeTransactionsStopped.get(CLOSE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            reportLayerError(new NonInviteTransactionLayerException(
                    "interrupted while closing Non-INVITE transactions",
                    exception
            ));
        } catch (Exception exception) {
            reportLayerError(new NonInviteTransactionLayerException(
                    "timed out while closing Non-INVITE transactions",
                    exception
            ));
        }

        if (ownsScheduler) {
            scheduler.close();
            shutdownExecutor(ownedCallbackExecutor);
            shutdownExecutor(ownedTransactionExecutor);
        }
    }

    private void dispatchRequest(SipRequest request, TransportContext context) {
        try {
            rejectSpecialMethod(request.method());
            TransactionKey key = TransactionKeyFactory.fromRequest(request);
            NonInviteServerTransaction transaction;
            synchronized (lifecycleMonitor) {
                if (closed.get()) {
                    return;
                }
                SipTransaction selected = serverTransactions.getOrCreate(key, () ->
                        new NonInviteServerTransaction(
                                key,
                                TransportReliability.from(context.protocol()),
                                sender,
                                timerConfig,
                                scheduler,
                                transactionExecutor,
                                callbackExecutor,
                                config,
                                serverListener,
                                this::serverTerminated
                        )
                );
                transaction = (NonInviteServerTransaction) selected;
                activeServers.add(transaction);
            }
            transaction.receive(new RequestReceived(request, context));
        } catch (Throwable cause) {
            reportLayerError(new NonInviteTransactionLayerException(
                    "failed to dispatch Non-INVITE request",
                    cause
            ));
        }
    }

    private void dispatchResponse(SipResponse response, TransportContext context) {
        try {
            TransactionKey key = TransactionKeyFactory.fromResponse(response);
            SipTransaction selected = clientTransactions.find(key).orElseThrow(
                    () -> new NonInviteTransactionLayerException("unmatched Non-INVITE response: " + key)
            );
            ((NonInviteClientTransaction) selected).receive(new ResponseReceived(response, context));
        } catch (Throwable cause) {
            reportLayerError(new NonInviteTransactionLayerException(
                    "failed to dispatch Non-INVITE response",
                    cause
            ));
        }
    }

    private void clientTerminated(SipTransaction transaction) {
        clientTransactions.remove(transaction.key(), transaction);
        activeClients.remove(transaction);
        signalStoppedIfEmpty();
    }

    private void serverTerminated(SipTransaction transaction) {
        serverTransactions.remove(transaction.key(), transaction);
        activeServers.remove(transaction);
        signalStoppedIfEmpty();
    }

    private void signalStoppedIfEmpty() {
        if (closed.get() && activeClients.isEmpty() && activeServers.isEmpty()) {
            activeTransactionsStopped.complete(null);
        }
    }

    private void reportLayerError(Throwable cause) {
        try {
            clientListener.onLayerError(cause);
        } catch (Throwable ignored) {
            // Server listener still gets a chance to observe the failure.
        }
        try {
            serverListener.onLayerError(cause);
        } catch (Throwable ignored) {
            System.getLogger(getClass().getName()).log(
                    System.Logger.Level.WARNING,
                    "Non-INVITE layer error listener failed",
                    ignored
            );
        }
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("Non-INVITE transaction manager is closed");
        }
    }

    private static void rejectSpecialMethod(SipMethod method) {
        if (SipMethod.INVITE.equals(method)
                || SipMethod.ACK.equals(method)
                || SipMethod.CANCEL.equals(method)) {
            throw new IllegalArgumentException("method is outside 3B Non-INVITE scope: " + method);
        }
    }

    private static ExecutorService newVirtualExecutor(String prefix) {
        return Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name(prefix, 0).factory());
    }

    private static void shutdownExecutor(ExecutorService executor) {
        executor.shutdownNow();
        if (!Thread.currentThread().isVirtual()) {
            try {
                executor.awaitTermination(CLOSE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
