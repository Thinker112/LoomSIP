package org.loomsip.dialog;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/** Command atomically allocating the next local CSeq. */
record DialogLocalSequenceRequested(CompletableFuture<Long> result) implements DialogEvent {

    DialogLocalSequenceRequested {
        Objects.requireNonNull(result, "result");
    }
}
