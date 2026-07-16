package org.loomsip.transport;

import java.util.Objects;

/**
 * Successful completion of one transport write.
 *
 * @param localEndpoint endpoint that sent the encoded message
 * @param remoteEndpoint destination endpoint
 * @param encodedBytes number of encoded bytes submitted to the network channel
 */
public record SendResult(
        TransportEndpoint localEndpoint,
        TransportEndpoint remoteEndpoint,
        int encodedBytes
) {

    /**
     * Validates and creates a send result.
     *
     * @throws NullPointerException if an endpoint is {@code null}
     * @throws IllegalArgumentException if {@code encodedBytes} is negative
     */
    public SendResult {
        Objects.requireNonNull(localEndpoint, "localEndpoint");
        Objects.requireNonNull(remoteEndpoint, "remoteEndpoint");
        if (encodedBytes < 0) {
            throw new IllegalArgumentException("encodedBytes must not be negative");
        }
    }
}
