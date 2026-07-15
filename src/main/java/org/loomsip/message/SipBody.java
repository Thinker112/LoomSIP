package org.loomsip.message;

import java.util.Arrays;
import java.util.Objects;

/**
 * Immutable binary content of a SIP message.
 *
 * <p>Input and output arrays are defensively copied, so callers cannot mutate
 * a message body after construction.</p>
 */
public final class SipBody {

    private static final SipBody EMPTY = new SipBody(new byte[0], false);

    private final byte[] data;

    private SipBody(byte[] data, boolean copy) {
        this.data = copy ? data.clone() : data;
    }

    /**
     * Returns the shared empty body value.
     *
     * @return an immutable body containing zero bytes
     */
    public static SipBody empty() {
        return EMPTY;
    }

    /**
     * Creates an immutable body by copying the supplied bytes.
     *
     * @param data body bytes; must not be {@code null}
     * @return an immutable body containing the copied bytes
     * @throws NullPointerException if {@code data} is {@code null}
     */
    public static SipBody of(byte[] data) {
        Objects.requireNonNull(data, "data");
        return data.length == 0 ? EMPTY : new SipBody(data, true);
    }

    /**
     * Returns a copy of the body bytes.
     *
     * @return a new byte array for each invocation
     */
    public byte[] bytes() {
        return data.clone();
    }

    /**
     * Returns the body length used to calculate {@code Content-Length}.
     *
     * @return body length in bytes
     */
    public int length() {
        return data.length;
    }

    /**
     * Indicates whether this body contains no bytes.
     *
     * @return {@code true} when the byte length is zero
     */
    public boolean isEmpty() {
        return data.length == 0;
    }

    @Override
    public boolean equals(Object object) {
        return object == this || object instanceof SipBody that && Arrays.equals(data, that.data);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(data);
    }

    @Override
    public String toString() {
        return "SipBody[length=" + data.length + "]";
    }
}
