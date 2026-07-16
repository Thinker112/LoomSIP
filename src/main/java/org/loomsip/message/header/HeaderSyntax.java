package org.loomsip.message.header;

/**
 * Shared token and quoted-value validation for typed SIP header parsers.
 */
final class HeaderSyntax {

    private static final String SEPARATORS = "()<>@,;:\\\"/[]?={}";

    private HeaderSyntax() {
    }

    static String requireToken(String value, String label) {
        if (value.isEmpty()) {
            throw new IllegalArgumentException(label + " must not be empty");
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character <= 32 || character >= 127 || SEPARATORS.indexOf(character) >= 0) {
                throw new IllegalArgumentException(label + " contains an invalid token character at index " + index);
            }
        }
        return value;
    }
}
