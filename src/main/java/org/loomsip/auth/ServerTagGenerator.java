package org.loomsip.auth;

/** Generates one SIP To-tag for a UAS authentication challenge response. */
@FunctionalInterface
public interface ServerTagGenerator {

    /**
     * Returns a non-empty SIP token suitable for a To-tag.
     *
     * @return newly generated server tag
     */
    String nextTag();
}
