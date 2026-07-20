package org.loomsip.auth;

import java.util.Objects;
import java.util.Optional;

/** Result of validating one nonce before credential lookup and hash verification. */
public record DigestNonceValidation(DigestNonceStatus status, Optional<DigestNonce> nonce) {

    /** Validates that only active nonces carry metadata. */
    public DigestNonceValidation {
        status = Objects.requireNonNull(status, "status");
        nonce = Objects.requireNonNull(nonce, "nonce");
        if (status == DigestNonceStatus.VALID && nonce.isEmpty()) {
            throw new IllegalArgumentException("valid nonce status requires nonce metadata");
        }
        if (status != DigestNonceStatus.VALID && nonce.isPresent()) {
            throw new IllegalArgumentException("only valid nonce status may carry metadata");
        }
    }

    /**
     * Creates a status without active nonce metadata.
     *
     * @param status non-valid nonce status
     * @return immutable validation result
     */
    public static DigestNonceValidation withoutNonce(DigestNonceStatus status) {
        if (status == DigestNonceStatus.VALID) {
            throw new IllegalArgumentException("valid status requires nonce metadata");
        }
        return new DigestNonceValidation(status, Optional.empty());
    }
}
