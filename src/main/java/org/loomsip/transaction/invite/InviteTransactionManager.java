package org.loomsip.transaction.invite;

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
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Dispatcher and lifecycle owner for RFC 3261 INVITE transactions.
 *
 * <pre>{@code
 * InboundSipMessage
 *        |
 *        v
 * +--------------------------+
 * | InviteTransactionManager |
 * | INVITE -> IST repository |
 * | response -> ICT repo     |
 * | ACK -> related IST       |
 * +------------+-------------+
 *              |
 *       +------+------+
 *       |             |
 *       v             v
 *      ICT           IST
 *       |             |
 *       +------+------+
 *              v
 *      Transaction Mailbox
 * }</pre>
 */
public final class InviteTransactionManager implements SipMessageHandler, AutoCloseable {

    private static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(5);

    private final TransactionMessageSender sender;
    private final SipTimerConfig timerConfig;
    private final InviteTransactionConfig config;
    private final InviteClientListener clientListener;
    private final InviteServerListener serverListener;
    private final SipScheduler scheduler;
    private final Executor transactionExecutor;
    private final Executor callbackExecutor;
    private final ExecutorService ownedTransactionExecutor;
    private final ExecutorService ownedCallbackExecutor;
    private final boolean ownsResources;
    private final InMemoryTransactionRepository clientTransactions;
    private final InMemoryTransactionRepository serverTransactions;
    private final Set<InviteClientTransaction> activeClients = ConcurrentHashMap.newKeySet();
    private final Set<InviteServerTransaction> activeServers = ConcurrentHashMap.newKeySet();
    private final Object lifecycleMonitor = new Object();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final CompletableFuture<Void> activeTransactionsStopped = new CompletableFuture<>();

    /**
     * Creates a manager that owns its scheduler and virtual-thread executors.
     *
     * @param sender raw message sender
     * @param timerConfig SIP base timer configuration
     * @param config transaction and queue capacities
     * @param clientListener client TU notifications
     * @param serverListener server TU notifications
     */
    public InviteTransactionManager(
            TransactionMessageSender sender,
            SipTimerConfig timerConfig,
            InviteTransactionConfig config,
            InviteClientListener clientListener,
            InviteServerListener serverListener
    ) {
        this(
                sender,
                timerConfig,
                config,
                clientListener,
                serverListener,
                new DefaultSipScheduler(),
                newVirtualExecutor("loomsip-invite-tx-"),
                newVirtualExecutor("loomsip-invite-tu-"),
                true
        );
    }

    /**
     * Creates a manager using externally owned scheduling and execution resources.
     *
     * <p>This constructor supports deterministic tests. Closing the manager does
     * not close the supplied scheduler or executors.</p>
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
    public InviteTransactionManager(
            TransactionMessageSender sender,
            SipTimerConfig timerConfig,
            InviteTransactionConfig config,
            InviteClientListener clientListener,
            InviteServerListener serverListener,
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

    private InviteTransactionManager(
            TransactionMessageSender sender,
            SipTimerConfig timerConfig,
            InviteTransactionConfig config,
            InviteClientListener clientListener,
            InviteServerListener serverListener,
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
        this.ownsResources = ownsResources;
        ownedTransactionExecutor = ownsResources ? (ExecutorService) transactionExecutor : null;
        ownedCallbackExecutor = ownsResources ? (ExecutorService) callbackExecutor : null;
        clientTransactions = new InMemoryTransactionRepository(config.clientTransactions());
        serverTransactions = new InMemoryTransactionRepository(config.serverTransactions());
    }

    /**
     * Registers and starts an INVITE client transaction.
     *
     * @param invite INVITE request
     * @param target selected remote endpoint
     * @return client transaction handle
     * @throws TransactionKeyException if routing headers are malformed
     */
    public InviteClientHandle sendInvite(SipRequest invite, TransportEndpoint target)
            throws TransactionKeyException {
        Objects.requireNonNull(invite, "invite");
        Objects.requireNonNull(target, "target");
        requireMethod(invite, SipMethod.INVITE);

        TransactionKey key = TransactionKeyFactory.fromRequest(invite);
        InviteClientTransaction transaction = new InviteClientTransaction(
                key,
                invite,
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
        reportLayerError(new InviteTransactionLayerException(
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
     * @return active ICT count
     */
    public int activeClientTransactions() {
        return clientTransactions.size();
    }

    /**
     * Returns the active server transaction count.
     *
     * @return active IST count
     */
    public int activeServerTransactions() {
        return serverTransactions.size();
    }

    /** Terminates active transactions and releases resources owned by this manager. */
    @Override
    public void close() {
        synchronized (lifecycleMonitor) {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            if (activeClients.isEmpty() && activeServers.isEmpty()) {
                activeTransactionsStopped.complete(null);
            } else {
                activeClients.forEach(InviteClientTransaction::shutdown);
                activeServers.forEach(InviteServerTransaction::shutdown);
            }
        }

        try {
            activeTransactionsStopped.get(CLOSE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            reportLayerError(new InviteTransactionLayerException(
                    "interrupted while closing INVITE transactions",
                    exception
            ));
        } catch (Exception exception) {
            reportLayerError(new InviteTransactionLayerException(
                    "timed out while closing INVITE transactions",
                    exception
            ));
        }

        if (ownsResources) {
            scheduler.close();
            shutdownExecutor(ownedCallbackExecutor);
            shutdownExecutor(ownedTransactionExecutor);
        }
    }

    private void dispatchRequest(SipRequest request, TransportContext context) {
        try {
            if (SipMethod.INVITE.equals(request.method())) {
                dispatchInvite(request, context);
            } else if (SipMethod.ACK.equals(request.method())) {
                dispatchAck(request, context);
            } else {
                throw new IllegalArgumentException("method is outside 3C INVITE scope: " + request.method());
            }
        } catch (Throwable cause) {
            reportLayerError(new InviteTransactionLayerException(
                    "failed to dispatch INVITE-layer request",
                    cause
            ));
        }
    }

    private void dispatchInvite(SipRequest invite, TransportContext context) throws TransactionKeyException {
        TransactionKey key = TransactionKeyFactory.fromRequest(invite);
        InviteServerTransaction transaction;
        synchronized (lifecycleMonitor) {
            if (closed.get()) {
                return;
            }
            SipTransaction selected = serverTransactions.getOrCreate(key, () ->
                    new InviteServerTransaction(
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
            transaction = (InviteServerTransaction) selected;
            activeServers.add(transaction);
        }
        transaction.receive(new RequestReceived(invite, context));
    }

    private void dispatchAck(SipRequest ack, TransportContext context) throws TransactionKeyException {
        TransactionKey inviteKey = TransactionKeyFactory.forServerLookup(ack);
        SipTransaction selected = serverTransactions.find(inviteKey).orElse(null);
        if (selected instanceof InviteServerTransaction transaction && transaction.canConsumeAck()) {
            transaction.receive(new RequestReceived(ack, context));
        } else {
            notifyUnmatchedAck(ack, context);
        }
    }

    private void dispatchResponse(SipResponse response, TransportContext context) {
        try {
            TransactionKey key = TransactionKeyFactory.fromResponse(response);
            if (!SipMethod.INVITE.equals(key.method())) {
                throw new IllegalArgumentException("response CSeq is not INVITE");
            }
            SipTransaction selected = clientTransactions.find(key).orElseThrow(
                    () -> new InviteTransactionLayerException("unmatched INVITE response: " + key)
            );
            ((InviteClientTransaction) selected).receive(new ResponseReceived(response, context));
        } catch (Throwable cause) {
            reportLayerError(new InviteTransactionLayerException(
                    "failed to dispatch INVITE response",
                    cause
            ));
        }
    }

    private void notifyUnmatchedAck(SipRequest ack, TransportContext context) {
        try {
            callbackExecutor.execute(() -> {
                try {
                    serverListener.onUnmatchedAck(ack, context);
                } catch (Throwable cause) {
                    reportLayerError(cause);
                }
            });
        } catch (RejectedExecutionException exception) {
            reportLayerError(exception);
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
                    "INVITE layer error listener failed",
                    ignored
            );
        }
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("INVITE transaction manager is closed");
        }
    }

    private static void requireMethod(SipRequest request, SipMethod expected) {
        if (!expected.equals(request.method())) {
            throw new IllegalArgumentException("expected " + expected + " request but got " + request.method());
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
