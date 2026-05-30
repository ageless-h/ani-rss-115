package ani.rss.pan115.crypto;

import lombok.extern.slf4j.Slf4j;

/**
 * Factory for creating platform-appropriate KeychainProvider.
 */
@Slf4j
public class KeychainProviderFactory {

    private KeychainProviderFactory() {
    }

    /**
     * Get the best available KeychainProvider for the current platform.
     * Tries OS-native providers first, falls back to password-based provider.
     *
     * @return KeychainProvider instance
     */
    public static KeychainProvider getProvider() {
        String os = System.getProperty("os.name").toLowerCase();

        if (os.contains("mac")) {
            MacOSKeychainProvider provider = new MacOSKeychainProvider();
            if (provider.isAvailable()) {
                log.debug("Using macOS Keychain provider");
                return provider;
            }
        } else if (os.contains("windows")) {
            WindowsDPAPIProvider provider = new WindowsDPAPIProvider();
            if (provider.isAvailable()) {
                log.debug("Using Windows DPAPI provider");
                return provider;
            }
        } else if (os.contains("linux")) {
            LinuxSecretServiceProvider provider = new LinuxSecretServiceProvider();
            if (provider.isAvailable()) {
                log.debug("Using Linux Secret Service provider");
                return provider;
            }
        }

        log.warn("No OS keychain available, using fallback password provider");
        return new FallbackPasswordProvider();
    }
}
