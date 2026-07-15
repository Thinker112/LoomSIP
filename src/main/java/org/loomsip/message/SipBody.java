package org.loomsip.message;

import java.util.Arrays;
import java.util.Objects;

public final class SipBody {

    private static final SipBody EMPTY = new SipBody(new byte[0], false);

    private final byte[] data;

    private SipBody(byte[] data, boolean copy) {
        this.data = copy ? data.clone() : data;
    }

    public static SipBody empty() {
        return EMPTY;
    }

    public static SipBody of(byte[] data) {
        Objects.requireNonNull(data, "data");
        return data.length == 0 ? EMPTY : new SipBody(data, true);
    }

    public byte[] bytes() {
        return data.clone();
    }

    public int length() {
        return data.length;
    }

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
