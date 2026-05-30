package ani.rss.pan115.crypto;

import lombok.extern.slf4j.Slf4j;

import javax.crypto.SecretKey;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Base64;
import java.util.Set;

/**
 * Fallback provider that stores encrypted data in a file.
 * Requires a master password for encryption/decryption.
 */
@Slf4j
public class FallbackPasswordProvider implements KeychainProvider {

    private static final String CONFIG_DIR = ".config/ani-rss";
    private static final String KEY_FILE = "pan115-master.key";

    @Override
    public boolean storeSecret(String service, String account, byte[] secret) {
        try {
            Path configPath = getConfigPath();
            Files.createDirectories(configPath.getParent());

            // Set restrictive permissions (owner only)
            Set<PosixFilePermission> permissions = PosixFilePermissions.fromString("rw-------");
            Files.write(configPath, Base64.getEncoder().encode(secret));
            Files.setPosixFilePermissions(configPath, permissions);

            return true;
        } catch (Exception e) {
            log.error("Failed to store secret in fallback provider", e);
            return false;
        }
    }

    @Override
    public byte[] retrieveSecret(String service, String account) {
        try {
            Path configPath = getConfigPath();
            if (!Files.exists(configPath)) {
                return null;
            }

            byte[] encoded = Files.readAllBytes(configPath);
            return Base64.getDecoder().decode(encoded);
        } catch (Exception e) {
            log.error("Failed to retrieve secret from fallback provider", e);
            return null;
        }
    }

    @Override
    public boolean deleteSecret(String service, String account) {
        try {
            Path configPath = getConfigPath();
            return Files.deleteIfExists(configPath);
        } catch (IOException e) {
            log.error("Failed to delete secret from fallback provider", e);
            return false;
        }
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    private Path getConfigPath() {
        String home = System.getProperty("user.home");
        return Paths.get(home, CONFIG_DIR, KEY_FILE);
    }

    /**
     * Derive key from master password for file-based encryption.
     *
     * @param password Master password
     * @return Derived key with salt
     */
    public CryptoUtils.KeyWithSalt deriveKeyFromPassword(char[] password) {
        return CryptoUtils.deriveKeyWithSalt(password);
    }
}
