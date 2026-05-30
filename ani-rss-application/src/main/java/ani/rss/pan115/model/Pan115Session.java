package ani.rss.pan115.model;

import lombok.Data;

import java.time.Instant;

/**
 * 115 session information containing cookies.
 */
@Data
public class Pan115Session {
    private String uid;
    private String cid;
    private String seid;
    private String kid;
    private Instant expiresAt;

    /**
     * Convert session to cookie string format.
     *
     * @return Cookie string for HTTP requests
     */
    public String toCookieString() {
        return String.format("UID=%s;CID=%s;SEID=%s;KID=%s", uid, cid, seid, kid);
    }

    /**
     * Parse session from cookie string.
     *
     * @param cookies Cookie string
     * @return Pan115Session instance
     */
    public static Pan115Session fromCookieString(String cookies) {
        if (cookies == null || cookies.isEmpty()) {
            return null;
        }

        Pan115Session session = new Pan115Session();
        String[] parts = cookies.split(";");

        for (String part : parts) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length != 2) continue;

            switch (kv[0].toUpperCase()) {
                case "UID" -> session.setUid(kv[1]);
                case "CID" -> session.setCid(kv[1]);
                case "SEID" -> session.setSeid(kv[1]);
                case "KID" -> session.setKid(kv[1]);
            }
        }

        return session;
    }

    /**
     * Check if session is valid (has all required fields).
     *
     * @return true if valid
     */
    public boolean isValid() {
        return uid != null && !uid.isEmpty()
                && cid != null && !cid.isEmpty()
                && seid != null && !seid.isEmpty();
    }
}
