package org.loomsip.dialog;

import org.loomsip.message.SipMethod;

import java.util.Objects;

/**
 * Central method classification used by generic in-Dialog request handling.
 *
 * <pre>{@code
 * SipMethod
 *    |
 *    v
 * DialogMethodPolicy
 *    |
 *    +--> dedicated INVITE / BYE path
 *    +--> generic Non-INVITE path
 *    +--> reject ACK / CANCEL transaction-independent semantics
 * }</pre>
 *
 * <p>Stage 6A allows generic extensions only in Confirmed Dialogs. PRACK Early
 * Dialog rules and UPDATE target-refresh behavior are added by their owning
 * extension stages rather than being guessed here.</p>
 */
final class DialogMethodPolicy {

    private DialogMethodPolicy() {
    }

    static void requirePreparedRequest(SipMethod method, DialogState state) {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(state, "state");
        if (state != DialogState.CONFIRMED) {
            throw new IllegalStateException("in-Dialog requests require a confirmed Dialog");
        }
        if (SipMethod.ACK.equals(method) || SipMethod.CANCEL.equals(method)) {
            throw new IllegalArgumentException(
                    method + " cannot be created as a generic in-Dialog transaction"
            );
        }
    }

    static void requireGenericOutbound(SipMethod method) {
        Objects.requireNonNull(method, "method");
        if (SipMethod.INVITE.equals(method)
                || SipMethod.BYE.equals(method)
                || SipMethod.ACK.equals(method)
                || SipMethod.CANCEL.equals(method)) {
            throw new IllegalArgumentException(
                    method + " requires its dedicated Dialog or Transaction API"
            );
        }
    }

    static void requireInbound(SipMethod method, DialogState state)
            throws DialogRequestRejectedException {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(state, "state");
        if (state != DialogState.CONFIRMED) {
            throw new DialogRequestRejectedException(
                    481,
                    "Call/Transaction Does Not Exist",
                    "generic in-Dialog request requires a confirmed Dialog"
            );
        }
        if (SipMethod.ACK.equals(method) || SipMethod.CANCEL.equals(method)) {
            throw new DialogRequestRejectedException(
                    405,
                    "Method Not Allowed",
                    method + " is not processed as a generic in-Dialog request"
            );
        }
    }

    static boolean refreshesTarget(SipMethod method) {
        return SipMethod.INVITE.equals(method);
    }

    static boolean terminatesDialog(SipMethod method) {
        return SipMethod.BYE.equals(method);
    }
}
