package app.simsmartgsm.uitils;

public enum OtpSessionType {
    RENT("rent.otp.service"),      // chỉ nhận 1 OTP, xong đóng session
    BUY("buy.otp.service"),        // giữ session đến khi hết hạn (OTP SMS)
    SMS("buy.sms.service"),        // dịch vụ SMS
    CALL("buy.call.service");      // dịch vụ CALL

    private final String value;

    OtpSessionType(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static OtpSessionType fromString(String type) {
        for (OtpSessionType t : values()) {
            if (t.value.equalsIgnoreCase(type)) {
                return t;
            }
        }
        throw new IllegalArgumentException("Unknown type: " + type);
    }
}

