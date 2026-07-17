package org.loomsip.dialog;

import org.loomsip.message.header.ViaHeaderValue;
import org.loomsip.message.SipMessage;
import org.loomsip.transaction.TransactionMessageSender;
import org.loomsip.transaction.timer.SipScheduler;
import org.loomsip.transaction.timer.SipTimerConfig;
import org.loomsip.transport.SendResult;
import org.loomsip.transport.TransportEndpoint;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadFactory;
import java.util.function.Supplier;

/**
 * Shared I/O and timing dependencies used by Dialog protocol reliability.
 *
 * <p>The runtime does not own or close the sender, resolver, scheduler, or
 * branch supplier. The owning SIP stack controls their lifecycle.</p>
 *
 * @param sender raw transport write boundary
 * @param targetResolver next-hop resolver
 * @param scheduler shared SIP scheduler
 * @param timerConfig SIP T1/T2 timing values
 * @param branchSupplier supplier of complete RFC 3261 Via branch values
 * @param transportExecutor executor that isolates transport writes from Dialog Mailboxes
 */
public record DialogRuntime(
        TransactionMessageSender sender,
        DialogTargetResolver targetResolver,
        SipScheduler scheduler,
        SipTimerConfig timerConfig,
        Supplier<String> branchSupplier,
        Executor transportExecutor
) {

    private static final String TOKEN_SEPARATORS = "()<>@,;:\\\"/[]?={}";
    private static final ThreadFactory DEFAULT_TRANSPORT_THREAD_FACTORY =
            Thread.ofVirtual().name("loomsip-dialog-io-", 0).factory();
    private static final Executor DEFAULT_TRANSPORT_EXECUTOR = command ->
            DEFAULT_TRANSPORT_THREAD_FACTORY.newThread(command).start();

    /** Validates all runtime dependencies. */
    public DialogRuntime {
        Objects.requireNonNull(sender, "sender");
        Objects.requireNonNull(targetResolver, "targetResolver");
        Objects.requireNonNull(scheduler, "scheduler");
        Objects.requireNonNull(timerConfig, "timerConfig");
        Objects.requireNonNull(branchSupplier, "branchSupplier");
        Objects.requireNonNull(transportExecutor, "transportExecutor");
    }

    /**
     * Creates a runtime with transport writes dispatched on short-lived virtual threads.
     *
     * <p>The asynchronous default prevents a sender that immediately routes an inbound
     * message back into the stack from re-entering the Dialog Mailbox currently handling
     * a timer. Tests may inject a deterministic executor through the canonical constructor;
     * such an executor must also avoid synchronous re-entry when its sender can call back
     * into Dialog routing.</p>
     *
     * @param sender raw transport write boundary
     * @param targetResolver next-hop resolver
     * @param scheduler shared SIP scheduler
     * @param timerConfig SIP T1/T2 timing values
     * @param branchSupplier supplier of complete RFC 3261 Via branch values
     */
    public DialogRuntime(
            TransactionMessageSender sender,
            DialogTargetResolver targetResolver,
            SipScheduler scheduler,
            SipTimerConfig timerConfig,
            Supplier<String> branchSupplier
    ) {
        this(
                sender,
                targetResolver,
                scheduler,
                timerConfig,
                branchSupplier,
                DEFAULT_TRANSPORT_EXECUTOR
        );
    }

    /**
     * Creates a runtime using random RFC 3261 branch values.
     *
     * @param sender raw transport write boundary
     * @param targetResolver next-hop resolver
     * @param scheduler shared SIP scheduler
     * @param timerConfig SIP timing configuration
     * @return Dialog runtime
     */
    public static DialogRuntime create(
            TransactionMessageSender sender,
            DialogTargetResolver targetResolver,
            SipScheduler scheduler,
            SipTimerConfig timerConfig
    ) {
        return new DialogRuntime(
                sender,
                targetResolver,
                scheduler,
                timerConfig,
                () -> ViaHeaderValue.MAGIC_COOKIE + "-"
                        + UUID.randomUUID().toString().replace("-", ""),
                DEFAULT_TRANSPORT_EXECUTOR
        );
    }

    /**
     * Dispatches one Dialog-owned transport write outside the Dialog Mailbox call stack.
     *
     * @param message immutable SIP message
     * @param target resolved next hop
     * @return asynchronous send result
     */
    CompletionStage<SendResult> send(SipMessage message, TransportEndpoint target) {
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(target, "target");
        CompletableFuture<SendResult> result = new CompletableFuture<>();
        try {
            transportExecutor.execute(() -> {
                final CompletionStage<SendResult> sendStage;
                try {
                    sendStage = Objects.requireNonNull(sender.send(message, target), "sender result");
                } catch (Throwable cause) {
                    result.completeExceptionally(cause);
                    return;
                }
                sendStage.whenComplete((sendResult, failure) -> {
                    if (failure == null) {
                        result.complete(sendResult);
                    } else {
                        result.completeExceptionally(failure);
                    }
                });
            });
        } catch (Throwable cause) {
            result.completeExceptionally(cause);
        }
        return result.minimalCompletionStage();
    }

    <T> CompletionStage<T> execute(ThrowingSupplier<? extends T> action) {
        Objects.requireNonNull(action, "action");
        CompletableFuture<T> result = new CompletableFuture<>();
        try {
            transportExecutor.execute(() -> {
                try {
                    result.complete(action.get());
                } catch (Throwable cause) {
                    result.completeExceptionally(cause);
                }
            });
        } catch (Throwable cause) {
            result.completeExceptionally(cause);
        }
        return result.minimalCompletionStage();
    }

    String nextBranch() {
        String branch = Objects.requireNonNull(branchSupplier.get(), "branchSupplier result");
        if (!branch.regionMatches(
                true,
                0,
                ViaHeaderValue.MAGIC_COOKIE,
                0,
                ViaHeaderValue.MAGIC_COOKIE.length()
        ) || branch.chars().anyMatch(character ->
                character <= 32
                        || character >= 127
                        || TOKEN_SEPARATORS.indexOf(character) >= 0
        )) {
            throw new IllegalStateException("Dialog Via branch must start with z9hG4bK and contain ASCII tokens");
        }
        return branch;
    }

    @FunctionalInterface
    interface ThrowingSupplier<T> {
        T get() throws Exception;
    }
}
