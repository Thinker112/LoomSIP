package org.loomsip.stack;

import org.loomsip.transaction.SipTransactionDispatcher;
import org.loomsip.transaction.invite.InviteTransactionConfig;
import org.loomsip.transaction.invite.InviteTransactionManager;
import org.loomsip.transaction.noninvite.NonInviteTransactionConfig;
import org.loomsip.transaction.noninvite.NonInviteTransactionManager;
import org.loomsip.transaction.timer.SipTimerConfig;
import org.loomsip.transport.TransportRegistry;

import java.util.Objects;

/**
 * Stack-owned Transaction managers and their inbound dispatcher.
 *
 * <pre>{@code
 * SipTransport --> SipTransactionDispatcher --> IST / NIST --> TU bridge
 * }</pre>
 */
final class StackTransactionRuntime implements AutoCloseable {

    private final InviteTransactionManager inviteTransactions;
    private final NonInviteTransactionManager nonInviteTransactions;
    private final SipTransactionDispatcher dispatcher;

    StackTransactionRuntime(
            TransportRegistry registry,
            StackResources resources,
            TuHandlerRegistry handlers
    ) {
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(resources, "resources");
        TuServerTransactionBridge bridge = new TuServerTransactionBridge(
                Objects.requireNonNull(handlers, "handlers"),
                ignored -> { }
        );
        inviteTransactions = new InviteTransactionManager(
                registry::send,
                SipTimerConfig.DEFAULT,
                InviteTransactionConfig.DEFAULT,
                (transaction, response, context) -> { },
                bridge,
                resources.scheduler(),
                resources.callbackExecutor(),
                resources.callbackExecutor()
        );
        nonInviteTransactions = new NonInviteTransactionManager(
                registry::send,
                SipTimerConfig.DEFAULT,
                NonInviteTransactionConfig.DEFAULT,
                (transaction, response, context) -> { },
                bridge,
                resources.scheduler(),
                resources.callbackExecutor(),
                resources.callbackExecutor()
        );
        dispatcher = new SipTransactionDispatcher(inviteTransactions, nonInviteTransactions);
    }

    SipTransactionDispatcher dispatcher() {
        return dispatcher;
    }

    InviteTransactionManager inviteTransactions() {
        return inviteTransactions;
    }

    NonInviteTransactionManager nonInviteTransactions() {
        return nonInviteTransactions;
    }

    StackStateSnapshot snapshot(SipStackState state, java.util.List<StackTransportSnapshot> transports,
                                java.util.Optional<String> lastFailure) {
        return new StackStateSnapshot(state, transports,
                inviteTransactions.activeClientTransactions(), inviteTransactions.activeServerTransactions(),
                nonInviteTransactions.activeClientTransactions(), nonInviteTransactions.activeServerTransactions(), lastFailure);
    }

    @Override
    public void close() {
        RuntimeException failure = null;
        try {
            inviteTransactions.close();
        } catch (RuntimeException exception) {
            failure = exception;
        }
        try {
            nonInviteTransactions.close();
        } catch (RuntimeException exception) {
            if (failure == null) {
                failure = exception;
            } else {
                failure.addSuppressed(exception);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }
}
