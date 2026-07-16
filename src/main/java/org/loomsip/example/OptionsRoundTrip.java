package org.loomsip.example;

import org.loomsip.message.SipResponse;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Runs a local UDP OPTIONS request and response without a transaction layer.
 */
public final class OptionsRoundTrip {

    private OptionsRoundTrip() {
    }

    /**
     * Starts local client/server endpoints and prints the received final status.
     *
     * @param arguments ignored
     * @throws Exception if binding, sending, parsing, or the five-second wait fails
     */
    public static void main(String[] arguments) throws Exception {
        InetAddress loopback = InetAddress.getLoopbackAddress();
        InetSocketAddress ephemeralLoopback = new InetSocketAddress(loopback, 0);

        try (OptionsServer server = new OptionsServer(ephemeralLoopback);
             OptionsClient client = new OptionsClient(ephemeralLoopback)) {
            server.start();
            client.start();

            SipResponse response = client.sendOptions(server.localEndpoint())
                    .toCompletableFuture()
                    .get(Duration.ofSeconds(5).toMillis(), TimeUnit.MILLISECONDS);
            System.out.println(response.statusCode() + " " + response.reasonPhrase());
        }
    }
}
