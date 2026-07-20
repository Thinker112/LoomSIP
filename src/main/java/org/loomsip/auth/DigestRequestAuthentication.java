package org.loomsip.auth;

import org.loomsip.message.SipRequest;

import java.util.Objects;

/** Applies one calculated Digest authorization header to an immutable request. */
public final class DigestRequestAuthentication {

    private DigestRequestAuthentication() {
    }

    /**
     * Replaces the Authorization or Proxy-Authorization field for one scope.
     *
     * <p>The other authentication scope is retained. Callers still need to
     * replace Via and CSeq before submitting the new transaction attempt.</p>
     *
     * @param request immutable source request
     * @param scope origin or proxy scope
     * @param authorization calculated authorization parameters
     * @return rebuilt immutable request
     */
    public static SipRequest apply(
            SipRequest request,
            DigestAuthenticationScope scope,
            DigestAuthorization authorization
    ) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(authorization, "authorization");
        return request.toBuilder()
                .replaceHeader(scope.authorizationHeaderName(), authorization.wireValue())
                .build();
    }
}
