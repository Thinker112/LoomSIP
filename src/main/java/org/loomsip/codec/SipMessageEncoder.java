package org.loomsip.codec;

import org.loomsip.message.SipHeader;
import org.loomsip.message.SipHeaders;
import org.loomsip.message.SipMessage;
import org.loomsip.message.SipRequest;
import org.loomsip.message.SipResponse;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Encodes immutable SIP messages into complete wire-format byte arrays.
 *
 * <p>The encoder emits CRLF line endings and replaces all supplied
 * {@code Content-Length} fields with one value calculated from the binary body.</p>
 */
public final class SipMessageEncoder {

    private static final byte[] CRLF = {'\r', '\n'};

    /**
     * Creates a stateless message encoder.
     */
    public SipMessageEncoder() {
    }

    /**
     * Encodes one complete SIP request or response.
     *
     * @param message immutable message to encode
     * @return newly allocated wire-format bytes
     * @throws NullPointerException if {@code message} is {@code null}
     */
    public byte[] encode(SipMessage message) {
        Objects.requireNonNull(message, "message");

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeLine(output, startLine(message));

        boolean contentLengthWritten = false;
        for (SipHeader header : message.headers().entries()) {
            if (SipHeaders.namesEqual(header.name(), "Content-Length")) {
                if (!contentLengthWritten) {
                    writeHeader(output, "Content-Length", Integer.toString(message.body().length()));
                    contentLengthWritten = true;
                }
            } else {
                writeHeader(output, header.name(), header.value());
            }
        }
        if (!contentLengthWritten) {
            writeHeader(output, "Content-Length", Integer.toString(message.body().length()));
        }

        output.writeBytes(CRLF);
        output.writeBytes(message.body().bytes());
        return output.toByteArray();
    }

    private static String startLine(SipMessage message) {
        if (message instanceof SipRequest request) {
            return request.method() + " " + request.requestUri() + " " + request.version();
        }
        SipResponse response = (SipResponse) message;
        return response.version() + " " + response.statusCode() + " " + response.reasonPhrase();
    }

    private static void writeHeader(ByteArrayOutputStream output, String name, String value) {
        writeLine(output, name + ": " + value);
    }

    private static void writeLine(ByteArrayOutputStream output, String line) {
        output.writeBytes(line.getBytes(StandardCharsets.UTF_8));
        output.writeBytes(CRLF);
    }
}
