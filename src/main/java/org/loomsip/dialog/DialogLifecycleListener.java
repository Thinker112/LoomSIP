package org.loomsip.dialog;

/** Ordered TU notifications emitted by Dialog lifecycle infrastructure. */
public interface DialogLifecycleListener {

    /**
     * Reports a completed state transition.
     *
     * @param dialog Dialog handle
     * @param previous previous state
     * @param current current state
     */
    default void onStateChanged(DialogHandle dialog, DialogState previous, DialogState current) {
    }

    /**
     * Reports completed Dialog cleanup.
     *
     * @param dialog terminated Dialog
     * @param reason termination reason
     */
    default void onTerminated(DialogHandle dialog, DialogTerminationReason reason) {
    }

    /**
     * Reports infrastructure failure associated with one Dialog.
     *
     * @param dialog affected Dialog
     * @param cause failure
     */
    default void onFailure(DialogHandle dialog, Throwable cause) {
    }

    /**
     * Reports infrastructure failure associated with the manager itself.
     *
     * @param cause failure
     */
    default void onManagerFailure(Throwable cause) {
    }
}
