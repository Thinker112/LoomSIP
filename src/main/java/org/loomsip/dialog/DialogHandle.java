package org.loomsip.dialog;

import java.util.concurrent.CompletionStage;

/** Read-only application handle for a serialized SIP Dialog. */
public interface DialogHandle {

    /**
     * Returns the stable identity of this Dialog.
     *
     * @return stable Dialog identity
     */
    DialogId id();

    /**
     * Returns the latest externally visible state.
     *
     * @return latest immutable state snapshot
     */
    DialogSnapshot snapshot();

    /**
     * Returns lifecycle completion for this Dialog.
     *
     * @return stage completed after Dialog termination and callback drainage
     */
    CompletionStage<Void> terminated();
}
