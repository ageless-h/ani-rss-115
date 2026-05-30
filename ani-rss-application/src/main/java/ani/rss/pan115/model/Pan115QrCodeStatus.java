package ani.rss.pan115.model;

import lombok.Getter;

/**
 * QR code login status enumeration.
 */
@Getter
public enum Pan115QrCodeStatus {
    WAITING(0, "等待扫码"),
    SCANNED(1, "已扫码，等待确认"),
    CONFIRMED(2, "登录成功"),
    EXPIRED(-1, "二维码已过期"),
    CANCELED(-2, "用户取消");

    private final int code;
    private final String description;

    Pan115QrCodeStatus(int code, String description) {
        this.code = code;
        this.description = description;
    }

    public static Pan115QrCodeStatus fromCode(int code) {
        for (Pan115QrCodeStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        return EXPIRED;
    }
}
