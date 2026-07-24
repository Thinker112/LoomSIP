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
    private final org.loomsip.dialog.DialogManager dialogs;
    private final org.loomsip.subscription.SubscriptionManager subscriptions;

    StackTransactionRuntime(
            TransportRegistry registry,
            StackResources resources,
            SipStackApplication application,
            TuHandlerRegistry handlers,
            DialogStackConfig dialogConfig
    ) {
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(resources, "resources");
        java.util.function.Consumer<Throwable> applicationErrors = application == null
                ? ignored -> { } : application.errorListener();
        TuServerTransactionBridge tuBridge = new TuServerTransactionBridge(
                Objects.requireNonNull(handlers, "handlers"), applicationErrors
        );
        org.loomsip.info.InfoDispatcher infoDispatcher = new org.loomsip.info.InfoDispatcher();
        if (application != null) {
            application.infoHandlers().forEach((name, handler) -> infoDispatcher.register(
                    new org.loomsip.message.header.InfoPackageHeaderValue(name), handler
            ));
        }
        subscriptions = application == null ? null : new org.loomsip.subscription.SubscriptionManager(
                org.loomsip.subscription.SubscriptionConfig.DEFAULT, resources.callbackExecutor(),
                applicationErrors, resources.scheduler());
        org.loomsip.subscription.SubscriptionDispatcher subscriptionDispatcher = new org.loomsip.subscription.SubscriptionDispatcher();
        if (application != null) application.subscriptionHandlers().forEach((event, handler) -> {
            if (event.eventId().isEmpty()) subscriptionDispatcher.register(event, handler);
        });
        if (dialogConfig == null) {
            dialogs = null;
            inviteTransactions = new InviteTransactionManager(registry::send, SipTimerConfig.DEFAULT,
                    InviteTransactionConfig.DEFAULT, (transaction, response, context) -> { }, tuBridge,
                    resources.scheduler(), resources.callbackExecutor(), resources.callbackExecutor());
            org.loomsip.transaction.noninvite.NonInviteServerListener server = subscriptions == null ? tuBridge
                    : new org.loomsip.subscription.SubscriptionSubscribeServerListener(subscriptionDispatcher, subscriptions,
                    tuBridge, () -> java.util.UUID.randomUUID().toString().replace("-", ""), resources.callbackExecutor(), application.errorListener());
            if (application != null && application.referHandler().isPresent()) server = new org.loomsip.refer.ReferServerListener(
                    application.referHandler().orElseThrow(), subscriptions, application.referSubscriptionListener(), server,
                    resources.callbackExecutor(), applicationErrors, () -> java.util.UUID.randomUUID().toString().replace("-", ""));
            if (subscriptions != null) server = new org.loomsip.subscription.SubscriptionNotifyServerListener(
                    new org.loomsip.subscription.SubscriptionNotifyRouter(subscriptions), server, resources.callbackExecutor(), application.errorListener());
            org.loomsip.transaction.noninvite.NonInviteClientListener client = (transaction, response, context) -> { };
            if (subscriptions != null) client = new org.loomsip.subscription.SubscriptionSubscribeClientListener(
                    new org.loomsip.subscription.SubscriptionSubscribeResponseRouter(subscriptions), client,
                    resources.callbackExecutor(), application.errorListener());
            nonInviteTransactions = new NonInviteTransactionManager(registry::send, SipTimerConfig.DEFAULT,
                    NonInviteTransactionConfig.DEFAULT, client, server,
                    resources.scheduler(), resources.callbackExecutor(), resources.callbackExecutor());
            dispatcher = new SipTransactionDispatcher(inviteTransactions, nonInviteTransactions);
            return;
        }
        java.util.concurrent.atomic.AtomicReference<InviteTransactionManager> invites = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<NonInviteTransactionManager> nonInvites = new java.util.concurrent.atomic.AtomicReference<>();
        org.loomsip.dialog.DialogRuntime runtime = new org.loomsip.dialog.DialogRuntime(registry::send,
                dialogConfig.targetResolver(), resources.scheduler(), SipTimerConfig.DEFAULT,
                () -> "z9hG4bK-" + java.util.UUID.randomUUID(), resources.callbackExecutor());
        org.loomsip.dialog.DialogRequestRuntime requestRuntime = new org.loomsip.dialog.DialogRequestRuntime(runtime,
                dialogConfig.requestProfile(), new org.loomsip.dialog.DialogRequestDispatcher() {
                    public org.loomsip.transaction.invite.InviteClientHandle sendInvite(org.loomsip.message.SipRequest request, org.loomsip.transport.TransportEndpoint target) throws org.loomsip.transaction.TransactionKeyException { return invites.get().sendInvite(request, target); }
                    public org.loomsip.transaction.noninvite.ClientTransactionHandle sendNonInvite(org.loomsip.message.SipRequest request, org.loomsip.transport.TransportEndpoint target) throws org.loomsip.transaction.TransactionKeyException { return nonInvites.get().sendRequest(request, target); }
                });
        org.loomsip.dialog.DialogLifecycleListener configuredDialogListener = dialogConfig.lifecycleListener();
        org.loomsip.dialog.DialogLifecycleListener dialogListener = new org.loomsip.dialog.DialogLifecycleListener() {
            @Override public void onStateChanged(org.loomsip.dialog.DialogHandle dialog, org.loomsip.dialog.DialogState previous, org.loomsip.dialog.DialogState current) { configuredDialogListener.onStateChanged(dialog, previous, current); }
            @Override public void onTerminated(org.loomsip.dialog.DialogHandle dialog, org.loomsip.dialog.DialogTerminationReason reason) { configuredDialogListener.onTerminated(dialog, reason); }
            @Override public void onFailure(org.loomsip.dialog.DialogHandle dialog, Throwable cause) {
                try { configuredDialogListener.onFailure(dialog, cause); } catch (Throwable ignored) { }
                try { applicationErrors.accept(cause); } catch (Throwable ignored) { }
            }
            @Override public void onManagerFailure(Throwable cause) {
                try { configuredDialogListener.onManagerFailure(cause); } catch (Throwable ignored) { }
                try { applicationErrors.accept(cause); } catch (Throwable ignored) { }
            }
        };
        dialogs = new org.loomsip.dialog.DialogManager(dialogConfig.dialogConfig(), dialogListener, requestRuntime);
        org.loomsip.dialog.DialogTransactionBridge bridge = new org.loomsip.dialog.DialogTransactionBridge(
                dialogs, (transaction, response, context) -> { }, tuBridge,
                (transaction, response, context) -> { }, tuBridge, null, infoDispatcher);
        inviteTransactions = new InviteTransactionManager(
                registry::send,
                SipTimerConfig.DEFAULT,
                InviteTransactionConfig.DEFAULT,
                bridge.clientListener(), bridge.serverListener(),
                resources.scheduler(),
                resources.callbackExecutor(),
                resources.callbackExecutor()
        );
        org.loomsip.transaction.noninvite.NonInviteServerListener server = subscriptions == null ? bridge.nonInviteServerListener()
                : new org.loomsip.subscription.SubscriptionSubscribeServerListener(subscriptionDispatcher, subscriptions,
                bridge.nonInviteServerListener(), () -> java.util.UUID.randomUUID().toString().replace("-", ""), resources.callbackExecutor(), application.errorListener());
        if (application != null && application.referHandler().isPresent()) server = new org.loomsip.refer.ReferServerListener(
                application.referHandler().orElseThrow(), subscriptions, application.referSubscriptionListener(), server,
                resources.callbackExecutor(), applicationErrors, () -> java.util.UUID.randomUUID().toString().replace("-", ""));
        if (subscriptions != null) server = new org.loomsip.subscription.SubscriptionNotifyServerListener(
                new org.loomsip.subscription.SubscriptionNotifyRouter(subscriptions), server, resources.callbackExecutor(), application.errorListener());
        org.loomsip.transaction.noninvite.NonInviteClientListener client = bridge.nonInviteClientListener();
        if (subscriptions != null) client = new org.loomsip.subscription.SubscriptionSubscribeClientListener(
                new org.loomsip.subscription.SubscriptionSubscribeResponseRouter(subscriptions), client,
                resources.callbackExecutor(), application.errorListener());
        nonInviteTransactions = new NonInviteTransactionManager(
                registry::send,
                SipTimerConfig.DEFAULT,
                NonInviteTransactionConfig.DEFAULT,
                client, server,
                resources.scheduler(),
                resources.callbackExecutor(),
                resources.callbackExecutor()
        );
        invites.set(inviteTransactions); nonInvites.set(nonInviteTransactions);
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

    java.util.Optional<org.loomsip.dialog.DialogManager> dialogs() {
        return java.util.Optional.ofNullable(dialogs);
    }

    java.util.Optional<org.loomsip.subscription.SubscriptionManager> subscriptions() { return java.util.Optional.ofNullable(subscriptions); }

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
            if (dialogs != null) dialogs.close();
        } catch (RuntimeException exception) { failure = exception; }
        try { if (subscriptions != null) subscriptions.close(); } catch (RuntimeException exception) { if (failure == null) failure = exception; else failure.addSuppressed(exception); }
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
