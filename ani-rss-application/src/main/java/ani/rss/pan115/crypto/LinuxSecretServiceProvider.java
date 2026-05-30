package ani.rss.pan115.crypto;

import com.github.javakeyring.Keyring;
import lombok.extern.slf4j.Slf4j;

/**
 * Linux Secret Service implementation using java-keyring library.
 */
@Slf4j
public class LinuxSecretServiceProvider implements KeychainProvider {

    @Override
    public boolean storeSecret(String service, String account, byte[] secret) {
        try {
            Keyring keyring = Keyring.create();
            keyring.setPassword(service, account, bytesToString(secret));
            return true;
        } catch (Exception e) {
            log.error("Failed to store secret in Linux Secret Service", e);
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
            log.error("Failed to retrieve secret from Linux Secret Service", e);
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
            log.error("Failed to delete secret from Linux Secret Service", e);
            return false;
        }
    }

    @Override
    public boolean isAvailable() {
        try {
            String os = System.getProperty("os.name").toLowerCase();
            if (!os.contains("linux")) {
                return false;
            }
            // Check if Secret Service is available
            Keyring keyring = Keyring.create();
            return keyring != null;
        } catch (Exception e) {
            log.debug("Linux Secret Service not available", e);
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
