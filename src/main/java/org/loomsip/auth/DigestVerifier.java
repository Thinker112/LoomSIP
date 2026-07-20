package org.loomsip.auth;

import org.loomsip.message.SipRequest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Objects;

/** Stateless UAS verifier for supported qop-auth Digest authorization values. */
public final class DigestVerifier {

    /** Creates a stateless Digest verifier. */
    public DigestVerifier() {
    }

    /**
     * Verifies the URI, credential identity, and response hash in constant time.
     *
     * <p>Nonce expiry and replay protection remain the responsibility of
     * {@link DigestNonceManager}; callers invoke it before and after this pure
     * calculation respectively.</p>
     *
     * @param request immutable authenticated request
     * @param authorization parsed client authorization
     * @param credential matching precomputed HA1 credential
     * @param nonce server nonce selected by the authentication gate
     * @return valid only when all identity and hash checks pass
     */
    public DigestVerificationResult verify(
            SipRequest request,
            DigestAuthorizationRequest authorization,
            DigestCredentialRecord credential,
            DigestNonce nonce
    ) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(authorization, "authorization");
        Objects.requireNonNull(credential, "credential");
        Objects.requireNonNull(nonce, "nonce");
        if (!authorization.username().equals(credential.username())
                || !authorization.realm().equals(credential.realm())
                || authorization.algorithm() != credential.algorithm()
                || !authorization.realm().equals(nonce.realm())
                || authorization.algorithm() != nonce.algorithm()
                || !authorization.nonce().equals(nonce.value())
                || !authorization.uri().equals(request.requestUri().toString())) {
            return DigestVerificationResult.INVALID;
        }
        String expected = DigestCalculator.responseFromHa1(
                authorization.algorithm(),
                nonce.charset().charset(),
                credential.ha1(),
                request.method().value(),
                authorization.uri(),
                authorization.nonce(),
                authorization.nonceCount(),
                authorization.cnonce(),
                authorization.qop()
        );
        byte[] expectedBytes = expected.getBytes(StandardCharsets.US_ASCII);
        byte[] actualBytes = authorization.response().getBytes(StandardCharsets.US_ASCII);
        try {
            return MessageDigest.isEqual(expectedBytes, actualBytes)
                    ? DigestVerificationResult.VALID
                    : DigestVerificationResult.INVALID;
        } finally {
            java.util.Arrays.fill(expectedBytes, (byte) 0);
            java.util.Arrays.fill(actualBytes, (byte) 0);
        }
    }
}
