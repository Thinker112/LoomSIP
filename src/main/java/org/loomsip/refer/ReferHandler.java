package org.loomsip.refer;

import java.util.concurrent.CompletionStage;

/**
 * Application transfer-policy callback for one inbound REFER.
 *
 * <pre>{@code
 * ReferServerListener --> ReferHandler --> asynchronous ReferAcceptance
 * }</pre>
 */
@FunctionalInterface
public interface ReferHandler {

    /**
     * Decides whether the recipient accepts a referral without blocking a protocol mailbox.
     *
     * @param request parsed referral target and subscription preference
     * @return eventual final response decision
     */
    CompletionStage<ReferAcceptance> onRefer(ReferRequest request);
}
