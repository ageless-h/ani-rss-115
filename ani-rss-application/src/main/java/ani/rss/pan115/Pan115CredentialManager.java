package ani.rss.pan115;

import ani.rss.entity.Config;
import ani.rss.pan115.crypto.CryptoUtils;
import ani.rss.pan115.crypto.KeychainProvider;
import ani.rss.pan115.crypto.KeychainProviderFactory;
import lombok.extern.slf4j.Slf4j;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Manages 115 cloud storage credentials with secure encryption.
 * Session tokens are memory-only; persistent storage uses encrypted format.
 */
@Slf4j
public class Pan115CredentialManager {

    private static final String SERVICE_NAME = "ani-rss-pan115";
    private static final String ACCOUNT_CREDENTIALS = "credentials";

    private final KeychainProvider keychain;
    private final Config config;
    private final Object storageLock = new Object();

    // Memory-only session storage - NEVER persisted
    private final AtomicReference<String> currentSession = new AtomicReference<>();
    private final AtomicReference<Instant> sessionExpiry = new AtomicReference<>();

    public Pan115CredentialManager(Config config) {
        this.config = config;
        this.keychain = KeychainProviderFactory.getProvider();
    }

    /**
     * Initialize the credential manager.
     * Checks if keychain is available and functional.
     *
     * @return true if ready to use
     */
    public boolean initialize() {
        if (!keychain.isAvailable()) {
            log.warn("Keychain provider not available, using fallback");
        }
        return true;
    }

    /**
     * Store 115 credentials securely.
     * Credentials are encrypted before storage.
     *
     * @param cookies The cookie string (UID=...;CID=...;SEID=...;KID=...)
     * @return true if stored successfully
     */
    public boolean storeCredentials(String cookies) {
        synchronized (storageLock) {
            try {
                if (cookies == null || cookies.isEmpty()) {
                    log.warn("Attempted to store empty credentials");
                    return false;
                }

                // Try to retrieve existing key from keychain first
                byte[] keyBytes = keychain.retrieveSecret(SERVICE_NAME, ACCOUNT_CREDENTIALS);
                
                // Only generate new key if no key exists
                if (keyBytes == null) {
                    keyBytes = CryptoUtils.generateRandomBytes(32);
                    boolean keyStored = keychain.storeSecret(SERVICE_NAME, ACCOUNT_CREDENTIALS, keyBytes);
                    if (!keyStored) {
                        log.error("Failed to store encryption key in keychain");
                        return false;
                    }
                }
                
                SecretKey key = new javax.crypto.spec.SecretKeySpec(keyBytes, "ChaCha20");

                // Encrypt the credentials
                String encrypted = CryptoUtils.encrypt(cookies, key);

                // Store the encrypted credentials in config
                config.setPan115EncryptedCredentials(encrypted);

                // Wipe key from memory
                CryptoUtils.wipe(keyBytes);

                log.info("115 credentials stored securely");
                return true;
            } catch (Exception e) {
                log.error("Failed to store credentials", e);
                return false;
            }
        }
    }

    /**
     * Retrieve and decrypt 115 credentials.
     *
     * @return The cookie string, or null if not found
     */
    public String retrieveCredentials() {
        try {
            String encrypted = config.getPan115EncryptedCredentials();
            if (encrypted == null || encrypted.isEmpty()) {
                return null;
            }

            // Retrieve key from keychain
            byte[] keyBytes = keychain.retrieveSecret(SERVICE_NAME, ACCOUNT_CREDENTIALS);
            if (keyBytes == null) {
                log.error("Encryption key not found in keychain");
                return null;
            }

            SecretKey key = new javax.crypto.spec.SecretKeySpec(keyBytes, "ChaCha20");

            // Decrypt credentials
            String decrypted = CryptoUtils.decrypt(encrypted, key);

            // Wipe key from memory
            CryptoUtils.wipe(keyBytes);

            return decrypted;
        } catch (Exception e) {
            log.error("Failed to retrieve credentials", e);
            return null;
        }
    }

    /**
     * Get current session (memory-only, never persisted).
     * Returns null if session is expired.
     *
     * @return Current session cookies, or null
     */
    public String getCurrentSession() {
        Instant expiry = sessionExpiry.get();
        if (expiry != null && Instant.now().isAfter(expiry)) {
            log.debug("Session expired, clearing");
            clearSession();
            return null;
        }
        return currentSession.get();
    }

    /**
     * Set current session (memory-only, never persisted).
     *
     * @param cookies The session cookie string
     * @param expiry  Session expiration time
     */
    public void setCurrentSession(String cookies, Instant expiry) {
        currentSession.set(cookies);
        sessionExpiry.set(expiry);
        log.debug("Session set, expires at {}", expiry);
    }

    /**
     * Clear session from memory.
     */
    public void clearSession() {
        currentSession.set(null);
        sessionExpiry.set(null);
        log.debug("Session cleared from memory");
    }

    /**
     * Check if credentials are stored.
     *
     * @return true if credentials exist
     */
    public boolean hasCredentials() {
        return config.getPan115EncryptedCredentials() != null &&
                !config.getPan115EncryptedCredentials().isEmpty();
    }

    /**
     * Wipe all stored credentials.
     */
    public void wipeAll() {
        synchronized (storageLock) {
            clearSession();
            config.setPan115EncryptedCredentials(null);
            boolean deleted = keychain.deleteSecret(SERVICE_NAME, ACCOUNT_CREDENTIALS);
            if (!deleted) {
                log.warn("Failed to delete key from keychain, may require manual cleanup");
            }
            log.info("All 115 credentials wiped");
        }
    }
}
