package org.loomsip.codec;

public record SipParserLimits(
        int maxStartLineBytes,
        int maxHeaderBytes,
        int maxBodyBytes
) {

    public static final SipParserLimits DEFAULT = new SipParserLimits(4_096, 65_536, 1_048_576);

    public SipParserLimits {
        if (maxStartLineBytes <= 0 || maxHeaderBytes <= 0 || maxBodyBytes < 0) {
            throw new IllegalArgumentException("parser limits must be positive (body may be zero)");
        }
    }
}
