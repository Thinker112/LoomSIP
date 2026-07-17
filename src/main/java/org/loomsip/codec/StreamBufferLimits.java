package org.loomsip.codec;

import java.util.Objects;

/**
 * Incremental resource limits for one stream connection.
 *
 * <p>{@link SipParserLimits} validates a complete framed message. These limits
 * additionally bound the bytes retained while a stream message is incomplete.</p>
 *
 * @param maxStartLineBytes maximum start-line bytes excluding CRLF
 * @param maxHeaderBytes maximum Header section bytes excluding the start-line
 * @param maxBodyBytes maximum binary Body bytes
 * @param maxMessageBytes maximum complete framed message bytes
 * @param maxCumulationBytes maximum incomplete bytes retained by the framer
 */
public record StreamBufferLimits(
        int maxStartLineBytes,
        int maxHeaderBytes,
        int maxBodyBytes,
        int maxMessageBytes,
        int maxCumulationBytes
) {

    /** Defaults aligned with {@link SipParserLimits#DEFAULT}. */
    public static final StreamBufferLimits DEFAULT = new StreamBufferLimits(
            SipParserLimits.DEFAULT.maxStartLineBytes(),
            SipParserLimits.DEFAULT.maxHeaderBytes(),
            SipParserLimits.DEFAULT.maxBodyBytes(),
            1_118_214,
            2_097_152
    );

    /** Validates stream limits and their aggregate ordering. */
    public StreamBufferLimits {
        if (maxStartLineBytes <= 0
                || maxHeaderBytes <= 0
                || maxBodyBytes < 0
                || maxMessageBytes <= 0
                || maxCumulationBytes <= 0) {
            throw new IllegalArgumentException(
                    "stream limits must be positive (body may be zero)"
            );
        }
        if (maxMessageBytes > maxCumulationBytes) {
            throw new IllegalArgumentException(
                    "maxMessageBytes must not exceed maxCumulationBytes"
            );
        }
    }

    /**
     * Creates stream limits using the parser limits and explicit stream bounds.
     *
     * @param parserLimits complete-message parser limits
     * @param maxMessageBytes maximum framed message bytes
     * @param maxCumulationBytes maximum incomplete bytes
     * @return stream limits
     */
    public static StreamBufferLimits of(
            SipParserLimits parserLimits,
            int maxMessageBytes,
            int maxCumulationBytes
    ) {
        Objects.requireNonNull(parserLimits, "parserLimits");
        return new StreamBufferLimits(
                parserLimits.maxStartLineBytes(),
                parserLimits.maxHeaderBytes(),
                parserLimits.maxBodyBytes(),
                maxMessageBytes,
                maxCumulationBytes
        );
    }
}
