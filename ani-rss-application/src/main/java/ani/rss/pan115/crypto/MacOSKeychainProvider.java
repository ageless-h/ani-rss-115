package ani.rss.pan115.crypto;

import com.github.javakeyring.Keyring;
import lombok.extern.slf4j.Slf4j;

/**
 * macOS Keychain implementation using java-keyring library.
 */
@Slf4j
public class MacOSKeychainProvider implements KeychainProvider {

    @Override
    public boolean storeSecret(String service, String account, byte[] secret) {
        try {
            Keyring keyring = Keyring.create();
            keyring.setPassword(service, account, bytesToString(secret));
            return true;
        } catch (Exception e) {
            log.error("Failed to store secret in macOS Keychain", e);
            return false;
        }
    }

    @Override
    public byte[] retrieveSecret(String service, String account) {
        try {
            Keyring keyring = Keyring.create();
            String password = keyring.getPassword(service, account);
            return password != null ? stringToBytes(password) : null;
        } catch (Exception e) {
            // java-keyring 在条目不存在时会抛 PasswordRetrievalException，这是首次使用的正常情况，
            // 不能以 ERROR 级别记录，否则会污染日志且看起来像故障。
            log.debug("Secret not present in macOS Keychain (service={}, account={}): {}",
                    service, account, e.getMessage());
            return null;
        }
    }

    @Override
    public boolean deleteSecret(String service, String account) {
        try {
            Keyring keyring = Keyring.create();
            keyring.deletePassword(service, account);
            return true;
        } catch (Exception e) {
            log.error("Failed to delete secret from macOS Keychain", e);
            return false;
        }
    }

    @Override
    public boolean isAvailable() {
        try {
            String os = System.getProperty("os.name").toLowerCase();
            return os.contains("mac");
        } catch (Exception e) {
            return false;
        }
    }

    private String bytesToString(byte[] bytes) {
        return java.util.Base64.getEncoder().encodeToString(bytes);
    }

    private byte[] stringToBytes(String str) {
        return java.util.Base64.getDecoder().decode(str);
    }
}
