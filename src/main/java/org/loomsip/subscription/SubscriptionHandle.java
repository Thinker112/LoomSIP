package org.loomsip.subscription;

import java.util.concurrent.CompletionStage;

/** Read-only handle for one mailbox-serialized Subscription. */
public interface SubscriptionHandle {

    /** @return stable subscription identity */
    SubscriptionId id();

    /** @return latest immutable lifecycle snapshot */
    SubscriptionSnapshot snapshot();

    /** @return completion after terminal cleanup and mailbox closure */
    CompletionStage<Void> terminated();
}
