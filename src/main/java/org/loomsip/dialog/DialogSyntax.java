package org.loomsip.dialog;

/** Validation shared by Dialog identity values. */
final class DialogSyntax {

    private static final String SEPARATORS = "()<>@,;:\\\"/[]?={}";

    private DialogSyntax() {
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

    static String requireCallId(String value) {
        if (value.isBlank() || value.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("Call-ID must not be blank or contain whitespace");
        }
        return value;
    }
}
