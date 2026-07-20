package org.loomsip.auth;

/** Maps origin and proxy challenges to their SIP status and header names. */
public enum DigestAuthenticationScope {
    /** Origin-server authentication using 401 and Authorization. */
    ORIGIN(401, "WWW-Authenticate", "Authorization"),
    /** Proxy authentication using 407 and Proxy-Authorization. */
    PROXY(407, "Proxy-Authenticate", "Proxy-Authorization");

    private final int challengeStatus;
    private final String challengeHeaderName;
    private final String authorizationHeaderName;

    DigestAuthenticationScope(
            int challengeStatus,
            String challengeHeaderName,
            String authorizationHeaderName
    ) {
        this.challengeStatus = challengeStatus;
        this.challengeHeaderName = challengeHeaderName;
        this.authorizationHeaderName = authorizationHeaderName;
    }

    /**
     * Finds the scope for a final response status.
     *
     * @param statusCode response status
     * @return matching scope, or {@code null} when it is not a Digest challenge status
     */
    public static DigestAuthenticationScope fromStatusCode(int statusCode) {
        return statusCode == ORIGIN.challengeStatus ? ORIGIN
                : statusCode == PROXY.challengeStatus ? PROXY
                : null;
    }

    /**
     * Returns the status code that carries this scope's challenge.
     *
     * @return challenge status code
     */
    public int challengeStatus() {
        return challengeStatus;
    }

    /**
     * Returns the response header name that carries challenges for this scope.
     *
     * @return response challenge header name
     */
    public String challengeHeaderName() {
        return challengeHeaderName;
    }

    /**
     * Returns the request header name used for this scope's authorization.
     *
     * @return retry request authorization header name
     */
    public String authorizationHeaderName() {
        return authorizationHeaderName;
    }
}
