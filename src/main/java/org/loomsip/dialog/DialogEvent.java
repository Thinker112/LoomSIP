package org.loomsip.dialog;

/** Marker for immutable commands serialized by one Dialog Mailbox. */
sealed interface DialogEvent permits
        DialogStateTransition,
        DialogRemoteTargetUpdate,
        DialogLocalSequenceRequested,
        DialogRemoteSequenceReceived,
        DialogRequestRequested,
        DialogInDialogRequestReceived,
        DialogUacSuccessReceived,
        DialogUacExchangeReleased,
        DialogUasSuccessRegistered,
        DialogUasExchangeReleased,
        DialogAckReceived,
        DialogTimerExpired,
        DialogReliabilityTransportFailed,
        DialogShutdown {
}
