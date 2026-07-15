package org.loomsip.message;

/**
 * Extensible SIP method token.
 *
 * <p>This is a value type rather than an enum so extension methods can pass
 * through the stack without code changes.</p>
 *
 * @param value case-sensitive SIP method token
 */
public record SipMethod(String value) {

    /** Standard {@code ACK} method. */
    public static final SipMethod ACK = new SipMethod("ACK");
    /** Standard {@code BYE} method. */
    public static final SipMethod BYE = new SipMethod("BYE");
    /** Standard {@code CANCEL} method. */
    public static final SipMethod CANCEL = new SipMethod("CANCEL");
    /** Standard {@code INVITE} method. */
    public static final SipMethod INVITE = new SipMethod("INVITE");
    /** Standard {@code OPTIONS} method. */
    public static final SipMethod OPTIONS = new SipMethod("OPTIONS");
    /** Standard {@code REGISTER} method. */
    public static final SipMethod REGISTER = new SipMethod("REGISTER");

    /**
     * Validates and creates a method value.
     *
     * @throws NullPointerException if {@code value} is {@code null}
     * @throws IllegalArgumentException if {@code value} is not a valid SIP token
     */
    public SipMethod {
        SipSyntax.requireToken(value, "method");
    }

    /**
     * Returns a shared standard constant when available, otherwise creates an
     * extension method value.
     *
     * @param value case-sensitive SIP method token
     * @return method value
     * @throws NullPointerException if {@code value} is {@code null}
     * @throws IllegalArgumentException if {@code value} is not a valid SIP token
     */
    public static SipMethod of(String value) {
        return switch (value) {
            case "ACK" -> ACK;
            case "BYE" -> BYE;
            case "CANCEL" -> CANCEL;
            case "INVITE" -> INVITE;
            case "OPTIONS" -> OPTIONS;
            case "REGISTER" -> REGISTER;
            default -> new SipMethod(value);
        };
    }

    @Override
    public String toString() {
        return value;
    }
}
