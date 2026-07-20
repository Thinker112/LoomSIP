package org.loomsip.dialog;

import org.loomsip.message.SipMethod;

/** Method selected to refresh an RFC 4028 Dialog session. */
public enum SessionRefreshMethod {
    /** Refresh through a target-refresh UPDATE transaction. */
    UPDATE(SipMethod.UPDATE),
    /** Refresh through an in-Dialog re-INVITE transaction. */
    INVITE(SipMethod.INVITE);

    private final SipMethod method;

    SessionRefreshMethod(SipMethod method) {
        this.method = method;
    }

    /** @return SIP refresh method */
    public SipMethod method() {
        return method;
    }
}
