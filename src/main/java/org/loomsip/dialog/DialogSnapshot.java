package org.loomsip.dialog;

import org.loomsip.message.SipUri;
import org.loomsip.message.header.CSeqHeaderValue;
import org.loomsip.message.header.RouteHeaderValue;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable externally visible Dialog state.
 *
 * @param id Dialog identity
 * @param role local endpoint role
 * @param state lifecycle state
 * @param localUri local address-of-record URI
 * @param remoteUri remote address-of-record URI
 * @param localCSeq latest locally allocated sequence number
 * @param remoteCSeq latest accepted remote sequence number
 * @param routeSet immutable route set in local request order
 * @param remoteTarget current remote Contact URI, absent for an incomplete Early Dialog
 * @param secure whether the Dialog requires secure transport
 */
public record DialogSnapshot(
        DialogId id,
        DialogRole role,
        DialogState state,
        SipUri localUri,
        SipUri remoteUri,
        long localCSeq,
        long remoteCSeq,
        List<RouteHeaderValue> routeSet,
        Optional<SipUri> remoteTarget,
        boolean secure
) {

    /** Validates and defensively copies Dialog state. */
    public DialogSnapshot {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(localUri, "localUri");
        Objects.requireNonNull(remoteUri, "remoteUri");
        routeSet = List.copyOf(Objects.requireNonNull(routeSet, "routeSet"));
        Objects.requireNonNull(remoteTarget, "remoteTarget");
        if (localCSeq < 0 || localCSeq > CSeqHeaderValue.MAX_SEQUENCE_NUMBER
                || remoteCSeq < 0 || remoteCSeq > CSeqHeaderValue.MAX_SEQUENCE_NUMBER) {
            throw new IllegalArgumentException("Dialog CSeq values must be valid SIP sequence numbers");
        }
        if (state == DialogState.CONFIRMED && remoteTarget.isEmpty()) {
            throw new IllegalArgumentException("Confirmed Dialog requires a remote target");
        }
    }

    /**
     * Returns the identity shared by fork-related Dialogs.
     *
     * @return Dialog Set identity for fork-related lookup
     */
    public DialogSetId setId() {
        return id.setId();
    }
}
