package org.loomsip.message;

public record SipHeader(String name, String value) {

    public SipHeader {
        SipSyntax.requireToken(name, "header name");
        SipSyntax.requireSingleLine(value, "header value");
    }
}
