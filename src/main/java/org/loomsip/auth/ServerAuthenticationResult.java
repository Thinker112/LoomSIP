package org.loomsip.auth;

import org.loomsip.message.SipResponse;

import java.util.Objects;
import java.util.Optional;

/** Immutable authentication-gate result for one UAS request. */
public record ServerAuthenticationResult(
        ServerAuthenticationDisposition disposition,
        Optional<SipResponse> challenge
) {

    /** Validates disposition and challenge response consistency. */
    public ServerAuthenticationResult {
        disposition = Objects.requireNonNull(disposition, "disposition");
        challenge = Objects.requireNonNull(challenge, "challenge");
        if (disposition == ServerAuthenticationDisposition.CHALLENGED && challenge.isEmpty()) {
            throw new IllegalArgumentException("challenged result requires a 401 response");
        }
        if (disposition == ServerAuthenticationDisposition.AUTHENTICATED && challenge.isPresent()) {
            throw new IllegalArgumentException("authenticated result must not contain a challenge");
        }
    }

    /**
     * Creates an authenticated result.
     *
     * @return successful authentication result
     */
    public static ServerAuthenticationResult authenticated() {
        return new ServerAuthenticationResult(ServerAuthenticationDisposition.AUTHENTICATED, Optional.empty());
    }

    /**
     * Creates a rejected result with one 401 challenge.
     *
     * @param response generic challenge response
     * @return challenge result
     */
    public static ServerAuthenticationResult challenged(SipResponse response) {
        return new ServerAuthenticationResult(
                ServerAuthenticationDisposition.CHALLENGED,
                Optional.of(Objects.requireNonNull(response, "response"))
        );
    }
}
