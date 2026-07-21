package org.loomsip.subscription;

import java.util.concurrent.CompletionStage;

/** Application handler for one registered UAS Event package. */
@FunctionalInterface
public interface SubscriptionHandler {

    /** Handles one parsed SUBSCRIBE without blocking a protocol mailbox. */
    CompletionStage<SubscriptionAcceptance> onSubscribe(SubscriptionEventRequest request);
}
