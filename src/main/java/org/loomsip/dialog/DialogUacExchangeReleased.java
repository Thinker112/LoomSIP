package org.loomsip.dialog;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/** Command releasing one cached UAC ACK after the ICT Accepted window ends. */
record DialogUacExchangeReleased(
        DialogInviteKey key,
        CompletableFuture<Void> result
) implements DialogEvent {

    DialogUacExchangeReleased {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(result, "result");
    }
}
