package org.loomsip.auth;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/** Character encodings permitted when calculating a supported Digest response. */
public enum DigestCharset {
    /** RFC 2617 default when a challenge omits the charset parameter. */
    ISO_8859_1(StandardCharsets.ISO_8859_1),
    /** RFC 7616 UTF-8 extension. */
    UTF_8(StandardCharsets.UTF_8);

    private final Charset charset;

    DigestCharset(Charset charset) {
        this.charset = charset;
    }

    /**
     * Returns the Java charset used for digest input bytes.
     *
     * @return configured charset
     */
    public Charset charset() {
        return charset;
    }

    /**
     * Parses a supported challenge charset token.
     *
     * @param value challenge charset token
     * @return supported charset
     * @throws DigestUnsupportedChallengeException if the charset is unsupported
     */
    public static DigestCharset parse(String value) {
        if ("UTF-8".equalsIgnoreCase(value)) {
            return UTF_8;
        }
        throw new DigestUnsupportedChallengeException("unsupported Digest charset: " + value);
    }
}
