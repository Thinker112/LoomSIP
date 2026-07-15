package org.loomsip.message;

public sealed interface SipMessage permits SipRequest, SipResponse {

    SipVersion version();

    SipHeaders headers();

    SipBody body();
}
