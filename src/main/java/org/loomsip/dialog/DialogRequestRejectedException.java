package org.loomsip.dialog;

/** Protocol rejection used to turn invalid in-Dialog requests into SIP responses. */
final class DialogRequestRejectedException extends RuntimeException {

    private final int statusCode;
    private final String reasonPhrase;

    DialogRequestRejectedException(int statusCode, String reasonPhrase, String message) {
        super(message);
        this.statusCode = statusCode;
        this.reasonPhrase = reasonPhrase;
    }

    int statusCode() {
        return statusCode;
    }

    String reasonPhrase() {
        return reasonPhrase;
    }
}
