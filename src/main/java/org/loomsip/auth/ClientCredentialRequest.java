package org.loomsip.auth;

import org.loomsip.message.SipRequest;

import java.util.Objects;

/**
 * Immutable lookup key supplied to a UAC credential provider.
 *
 * @param scope origin or proxy authentication scope
 * @param challenge selected server challenge
 * @param request immutable request that received the challenge
 */
public record ClientCredentialRequest(
        DigestAuthenticationScope scope,
        DigestChallenge challenge,
        SipRequest request
) {

    /** Validates credential lookup inputs. */
    public ClientCredentialRequest {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(challenge, "challenge");
        Objects.requireNonNull(request, "request");
    }
}
