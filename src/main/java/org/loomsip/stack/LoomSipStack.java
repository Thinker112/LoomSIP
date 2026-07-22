package org.loomsip.stack;

import java.util.concurrent.CompletionStage;

/**
 * Root lifecycle boundary for one embedded SIP protocol stack runtime.
 *
 * <pre>{@code
 * application --> LoomSipStack --> TransportRegistry
 *                         |
 *                         v
 *                   StackResources
 * }</pre>
 *
 * <p>This type owns startup and shutdown orchestration only. Transaction,
 * Dialog, and Subscription state remain owned by their individual Mailboxes;
 * no Stack-global protocol queue is introduced.</p>
 */
public interface LoomSipStack extends AutoCloseable {

    /**
     * Creates a builder for one Stack instance.
     *
     * @return mutable builder used only before {@link LoomSipStackBuilder#build()}
     */
    static LoomSipStackBuilder builder() {
        return new LoomSipStackBuilder();
    }

    /**
     * Returns the current Stack lifecycle state.
     *
     * @return immutable state snapshot
     */
    SipStackState state();

    /**
     * Returns the state-gated facade for outgoing protocol commands.
     *
     * @return reusable client facade; commands require the RUNNING state
     */
    SipClient client();

    /** @return immutable runtime diagnostic snapshot */
    StackStateSnapshot snapshot();

    /**
     * Starts owned transports exactly once.
     *
     * @return completion after every configured transport has started
     */
    CompletionStage<Void> start();

    /**
     * Begins idempotent Stack shutdown and resource release.
     *
     * @return completion after transport and owned resource closure
     */
    CompletionStage<Void> closeAsync();

    /**
     * Starts shutdown and waits up to the configured timeout.
     *
     * @throws IllegalStateException if shutdown fails, times out, or the caller is interrupted
     */
    @Override
    void close();
}
