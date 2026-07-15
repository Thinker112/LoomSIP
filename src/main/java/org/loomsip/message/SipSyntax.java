package org.loomsip.message;

import java.util.Objects;

final class SipSyntax {

    private static final String SEPARATORS = "()<>@,;:\\\"/[]?={}";

    private SipSyntax() {
    }

    static String requireToken(String value, String label) {
        Objects.requireNonNull(value, label);
        if (value.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be empty");
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c <= 32 || c >= 127 || SEPARATORS.indexOf(c) >= 0) {
                throw new IllegalArgumentException(label + " contains an invalid token character at index " + i);
            }
        }
        return value;
    }

    static String requireSingleLine(String value, String label) {
        Objects.requireNonNull(value, label);
        if (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw new IllegalArgumentException(label + " must not contain CR or LF");
        }
        return value;
    }
}
