package org.loomsip.dialog;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/** Command cancelling UAS reliability state after the initial 2xx send fails. */
record DialogUasExchangeReleased(
        DialogInviteKey key,
        CompletableFuture<Void> result
) implements DialogEvent {

    DialogUasExchangeReleased {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(result, "result");
    }
}
