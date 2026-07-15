package org.loomsip.message;

public record SipMethod(String value) {

    public static final SipMethod ACK = new SipMethod("ACK");
    public static final SipMethod BYE = new SipMethod("BYE");
    public static final SipMethod CANCEL = new SipMethod("CANCEL");
    public static final SipMethod INVITE = new SipMethod("INVITE");
    public static final SipMethod OPTIONS = new SipMethod("OPTIONS");
    public static final SipMethod REGISTER = new SipMethod("REGISTER");

    public SipMethod {
        SipSyntax.requireToken(value, "method");
    }

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
