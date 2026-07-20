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
    /** Standard {@code INFO} extension method. */
    public static final SipMethod INFO = new SipMethod("INFO");
    /** Standard {@code NOTIFY} extension method. */
    public static final SipMethod NOTIFY = new SipMethod("NOTIFY");
    /** Standard {@code OPTIONS} method. */
    public static final SipMethod OPTIONS = new SipMethod("OPTIONS");
    /** Standard {@code PRACK} extension method. */
    public static final SipMethod PRACK = new SipMethod("PRACK");
    /** Standard {@code REGISTER} method. */
    public static final SipMethod REGISTER = new SipMethod("REGISTER");
    /** Standard {@code REFER} extension method. */
    public static final SipMethod REFER = new SipMethod("REFER");
    /** Standard {@code SUBSCRIBE} extension method. */
    public static final SipMethod SUBSCRIBE = new SipMethod("SUBSCRIBE");
    /** Standard {@code UPDATE} extension method. */
    public static final SipMethod UPDATE = new SipMethod("UPDATE");

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
            case "INFO" -> INFO;
            case "INVITE" -> INVITE;
            case "NOTIFY" -> NOTIFY;
            case "OPTIONS" -> OPTIONS;
            case "PRACK" -> PRACK;
            case "REGISTER" -> REGISTER;
            case "REFER" -> REFER;
            case "SUBSCRIBE" -> SUBSCRIBE;
            case "UPDATE" -> UPDATE;
            default -> new SipMethod(value);
        };
    }

    @Override
    public String toString() {
        return value;
    }
}
