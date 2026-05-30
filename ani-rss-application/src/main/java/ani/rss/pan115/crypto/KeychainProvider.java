package ani.rss.pan115.crypto;

/**
 * Interface for OS-native secure credential storage.
 * Implementations use platform-specific keychain/credential managers.
 */
public interface KeychainProvider {

    String SERVICE_NAME = "ani-rss-pan115";
    String ACCOUNT_KEY = "master-key";

    /**
     * Store a secret in the OS keychain.
     *
     * @param service The service identifier
     * @param account The account identifier
     * @param secret  The secret bytes to store
     * @return true if successful
     */
    boolean storeSecret(String service, String account, byte[] secret);

    /**
     * Retrieve a secret from the OS keychain.
     *
     * @param service The service identifier
     * @param account The account identifier
     * @return The secret bytes, or null if not found
     */
    byte[] retrieveSecret(String service, String account);

    /**
     * Delete a secret from the OS keychain.
     *
     * @param service The service identifier
     * @param account The account identifier
     * @return true if successful
     */
    boolean deleteSecret(String service, String account);

    /**
     * Check if this provider is available on the current platform.
     *
     * @return true if available
     */
    boolean isAvailable();
}
