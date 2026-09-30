package io.hyperconduit.frame;

/** Wire codes for {@link Frame}. Private to the tunnel; not aligned with RFC 9000. */
public enum FrameType {
    STREAM_DATA(0x01),
    ACK(0x02),
    PING(0x03),
    MAX_DATA(0x04),
    CLOSE(0x05);

    private final int code;

    FrameType(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static FrameType fromCode(int code) {
        for (FrameType type : values()) {
            if (type.code == code) {
                return type;
            }
        }
        return null;
    }
}
