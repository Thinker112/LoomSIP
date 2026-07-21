package org.loomsip.testkit;

import org.loomsip.dialog.DialogConfig;
import org.loomsip.dialog.DialogLifecycleListener;
import org.loomsip.dialog.DialogManager;
import org.loomsip.dialog.DialogRequestDispatcher;
import org.loomsip.dialog.DialogRequestProfile;
import org.loomsip.dialog.DialogRequestRuntime;
import org.loomsip.dialog.DialogRuntime;
import org.loomsip.dialog.DialogTransactionBridge;
import org.loomsip.dialog.InMemoryDialogRepository;
import org.loomsip.info.InfoDispatcher;
import org.loomsip.message.SipRequest;
import org.loomsip.message.header.SentBy;
import org.loomsip.message.header.ViaTransport;
import org.loomsip.transaction.SipTransactionDispatcher;
import org.loomsip.transaction.TransactionKeyException;
import org.loomsip.transaction.TransactionMessageSender;
import org.loomsip.transaction.invite.InviteClientHandle;
import org.loomsip.transaction.invite.InviteClientListener;
import org.loomsip.transaction.invite.InviteServerListener;
import org.loomsip.transaction.invite.InviteTransactionConfig;
import org.loomsip.transaction.invite.InviteTransactionManager;
import org.loomsip.transaction.noninvite.ClientTransactionHandle;
import org.loomsip.transaction.noninvite.NonInviteClientListener;
import org.loomsip.transaction.noninvite.NonInviteServerListener;
import org.loomsip.transaction.noninvite.NonInviteTransactionConfig;
import org.loomsip.transaction.noninvite.NonInviteTransactionManager;
import org.loomsip.transaction.timer.SipTimerConfig;
import org.loomsip.transaction.timer.VirtualSipScheduler;
import org.loomsip.transport.SipTransport;
import org.loomsip.transport.TransportEndpoint;
import org.loomsip.transport.TransportProtocol;

import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Test-only assembly of one protocol endpoint over an existing transport.
 *
 * <pre>{@code
 * SipTransport --> Transaction Dispatcher --> Transaction Managers
 *                                             |
 *                                             v
 *                                      Dialog Bridge / Manager
 * }</pre>
 *
 * <p>This class deliberately does not create transports, TLS contexts, or a
 * production Stack API. Individual scenarios choose those dependencies and
 * supply the matching message sender.</p>
 */
public final class ScenarioEndpoint implements AutoCloseable {

    private final DialogManager dialogs;
    private final InviteTransactionManager inviteTransactions;
    private final NonInviteTransactionManager nonInviteTransactions;
    private final SipTransactionDispatcher dispatcher;
    private final VirtualSipScheduler scheduler;
    private final ExecutorService executor;

    private ScenarioEndpoint(
            DialogManager dialogs,
            InviteTransactionManager inviteTransactions,
            NonInviteTransactionManager nonInviteTransactions,
            VirtualSipScheduler scheduler,
            ExecutorService executor
    ) {
        this.dialogs = dialogs;
        this.inviteTransactions = inviteTransactions;
        this.nonInviteTransactions = nonInviteTransactions;
        dispatcher = new SipTransactionDispatcher(inviteTransactions, nonInviteTransactions);
        this.scheduler = scheduler;
        this.executor = executor;
    }

    /**
     * Assembles one endpoint with test-owned virtual scheduling and virtual threads.
     *
     * @param transport started local transport used for endpoint identity
     * @param sender raw or connection-aware sender selected by the scenario
     * @param remote resolved peer endpoint for Dialog-generated requests
     * @param timerConfig transaction timer configuration
     * @param lifecycle Dialog lifecycle observer
     * @param inviteClient INVITE client application listener
     * @param inviteServer INVITE server application listener
     * @param nonInviteClient Non-INVITE client application listener
     * @param nonInviteServer fallback Non-INVITE server application listener
     * @param infoDispatcher optional packaged INFO dispatcher
     * @return assembled endpoint
     */
    public static ScenarioEndpoint create(
            SipTransport transport,
            TransactionMessageSender sender,
            TransportEndpoint remote,
            SipTimerConfig timerConfig,
            DialogLifecycleListener lifecycle,
            InviteClientListener inviteClient,
            InviteServerListener inviteServer,
            NonInviteClientListener nonInviteClient,
            NonInviteServerListener nonInviteServer,
            InfoDispatcher infoDispatcher
    ) {
        Objects.requireNonNull(transport, "transport");
        TransportEndpoint local = transport.localEndpoint();
        VirtualSipScheduler scheduler = new VirtualSipScheduler();
        ExecutorService executor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("loomsip-6g-scenario-", 0).factory()
        );
        AtomicReference<ScenarioEndpoint> reference = new AtomicReference<>();
        AtomicInteger branches = new AtomicInteger();
        DialogRuntime runtime = new DialogRuntime(
                Objects.requireNonNull(sender, "sender"),
                (uri, protocol) -> java.util.concurrent.CompletableFuture.completedFuture(
                        Objects.requireNonNull(remote, "remote")
                ),
                scheduler,
                Objects.requireNonNull(timerConfig, "timerConfig"),
                () -> "z9hG4bK-6g-" + branches.incrementAndGet(),
                executor
        );
        DialogRequestDispatcher requestDispatcher = new DialogRequestDispatcher() {
            @Override
            public InviteClientHandle sendInvite(SipRequest request, TransportEndpoint target)
                    throws TransactionKeyException {
                return reference.get().inviteTransactions.sendInvite(request, target);
            }

            @Override
            public ClientTransactionHandle sendNonInvite(SipRequest request, TransportEndpoint target)
                    throws TransactionKeyException {
                return reference.get().nonInviteTransactions.sendRequest(request, target);
            }
        };
        DialogRequestRuntime requestRuntime = new DialogRequestRuntime(
                runtime,
                requestProfile(local),
                requestDispatcher
        );
        DialogManager dialogs = new DialogManager(
                new DialogConfig(16, 128, 64),
                Objects.requireNonNull(lifecycle, "lifecycle"),
                new InMemoryDialogRepository(16),
                executor,
                executor,
                requestRuntime
        );
        DialogTransactionBridge bridge = new DialogTransactionBridge(
                dialogs,
                Objects.requireNonNull(inviteClient, "inviteClient"),
                Objects.requireNonNull(inviteServer, "inviteServer"),
                Objects.requireNonNull(nonInviteClient, "nonInviteClient"),
                Objects.requireNonNull(nonInviteServer, "nonInviteServer"),
                null,
                infoDispatcher
        );
        InviteTransactionManager inviteTransactions = new InviteTransactionManager(
                sender,
                timerConfig,
                InviteTransactionConfig.DEFAULT,
                bridge.clientListener(),
                bridge.serverListener(),
                scheduler,
                executor,
                executor
        );
        NonInviteTransactionManager nonInviteTransactions = new NonInviteTransactionManager(
                sender,
                timerConfig,
                NonInviteTransactionConfig.DEFAULT,
                bridge.nonInviteClientListener(),
                bridge.nonInviteServerListener(),
                scheduler,
                executor,
                executor
        );
        ScenarioEndpoint endpoint = new ScenarioEndpoint(
                dialogs,
                inviteTransactions,
                nonInviteTransactions,
                scheduler,
                executor
        );
        reference.set(endpoint);
        return endpoint;
    }

    /** @return Dialog lifecycle owner */
    public DialogManager dialogs() {
        return dialogs;
    }

    /** @return INVITE transaction manager */
    public InviteTransactionManager inviteTransactions() {
        return inviteTransactions;
    }

    /** @return Non-INVITE transaction manager */
    public NonInviteTransactionManager nonInviteTransactions() {
        return nonInviteTransactions;
    }

    /** @return inbound transport dispatcher */
    public SipTransactionDispatcher dispatcher() {
        return dispatcher;
    }

    /** @return scenario-owned virtual scheduler */
    public VirtualSipScheduler scheduler() {
        return scheduler;
    }

    @Override
    public void close() {
        dialogs.close();
        inviteTransactions.close();
        nonInviteTransactions.close();
        scheduler.close();
        executor.shutdownNow();
    }

    private static DialogRequestProfile requestProfile(TransportEndpoint local) {
        TransportProtocol protocol = local.protocol();
        String host = local.address().getAddress() == null
                ? local.address().getHostString()
                : local.address().getAddress().getHostAddress();
        SentBy sentBy = new SentBy(host, local.address().getPort());
        return switch (protocol) {
            case UDP -> DialogRequestProfile.udp(sentBy);
            case TCP -> new DialogRequestProfile(ViaTransport.TCP, sentBy, protocol, false);
            case TLS -> new DialogRequestProfile(ViaTransport.TLS, sentBy, protocol, false);
        };
    }
}
