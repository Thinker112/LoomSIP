package org.loomsip.codec;

/** Incremental state of one TCP/TLS SIP stream decoder. */
public enum SipStreamDecoderState {
    /** Waiting for the complete start-line and Header section. */
    READING_HEADERS,
    /** Header boundary is known and the decoder is waiting for Body bytes. */
    READING_BODY
}
