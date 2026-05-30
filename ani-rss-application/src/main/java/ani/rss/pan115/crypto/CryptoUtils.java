package ani.rss.pan115.crypto;

import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.util.encoders.Base64;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.Security;
import java.util.Arrays;

/**
 * Cryptographic utilities for secure credential storage.
 * Uses ChaCha20-Poly1305 for encryption and Argon2id for key derivation.
 */
@Slf4j
public final class CryptoUtils {

    private static final String CIPHER_ALGORITHM = "ChaCha20-Poly1305";
    private static final String PROVIDER = "BC";
    private static final int KEY_SIZE = 32; // 256 bits
    private static final int NONCE_SIZE = 12; // 96 bits for ChaCha20
    private static final int SALT_SIZE = 16;
    private static final int ARGON2_MEMORY = 65536; // 64 MB
    private static final int ARGON2_ITERATIONS = 3;
    private static final int ARGON2_PARALLELISM = 4;

    static {
        Security.addProvider(new BouncyCastleProvider());
    }

    private CryptoUtils() {
        // Utility class
    }

    /**
     * Encrypt plaintext using ChaCha20-Poly1305.
     * Format: base64(salt || nonce || ciphertext)
     *
     * @param plaintext The text to encrypt
     * @param key       The encryption key
     * @return Base64-encoded encrypted data
     */
    public static String encrypt(String plaintext, SecretKey key) {
        try {
            byte[] salt = generateRandomBytes(SALT_SIZE);
            byte[] nonce = generateRandomBytes(NONCE_SIZE);

            Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM, PROVIDER);
            IvParameterSpec ivSpec = new IvParameterSpec(nonce);
            cipher.init(Cipher.ENCRYPT_MODE, key, ivSpec);

            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            // Combine: salt || nonce || ciphertext
            ByteBuffer buffer = ByteBuffer.allocate(salt.length + nonce.length + ciphertext.length);
            buffer.put(salt);
            buffer.put(nonce);
            buffer.put(ciphertext);

            return Base64.toBase64String(buffer.array());
        } catch (Exception e) {
            log.error("Encryption failed", e);
            throw new CryptoException("Failed to encrypt data", e);
        }
    }

    /**
     * Decrypt ciphertext using ChaCha20-Poly1305.
     * Expects format: base64(salt || nonce || ciphertext)
     *
     * @param ciphertext The encrypted data (base64-encoded)
     * @param key        The decryption key
     * @return The decrypted plaintext
     */
    public static String decrypt(String ciphertext, SecretKey key) {
        try {
            byte[] decoded = Base64.decode(ciphertext);

            ByteBuffer buffer = ByteBuffer.wrap(decoded);
            byte[] salt = new byte[SALT_SIZE];
            byte[] nonce = new byte[NONCE_SIZE];

            buffer.get(salt);
            buffer.get(nonce);

            byte[] encrypted = new byte[buffer.remaining()];
            buffer.get(encrypted);

            Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM, PROVIDER);
            IvParameterSpec ivSpec = new IvParameterSpec(nonce);
            cipher.init(Cipher.DECRYPT_MODE, key, ivSpec);

            byte[] decrypted = cipher.doFinal(encrypted);
            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("Decryption failed", e);
            throw new CryptoException("Failed to decrypt data", e);
        }
    }

    /**
     * Derive a key from password using Argon2id.
     *
     * @param password The password to derive from
     * @param salt     The salt for key derivation
     * @return The derived SecretKey
     */
    public static SecretKey deriveKey(char[] password, byte[] salt) {
        Argon2Parameters params = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withSalt(salt)
                .withParallelism(ARGON2_PARALLELISM)
                .withMemoryAsKB(ARGON2_MEMORY)
                .withIterations(ARGON2_ITERATIONS)
                .build();

        Argon2BytesGenerator generator = new Argon2BytesGenerator();
        generator.init(params);

        byte[] passwordBytes = charsToBytes(password);
        byte[] keyBytes = new byte[KEY_SIZE];
        generator.generateBytes(passwordBytes, keyBytes);

        wipe(passwordBytes);

        return new SecretKeySpec(keyBytes, "ChaCha20");
    }

    /**
     * Derive a key from password with generated salt.
     *
     * @param password The password to derive from
     * @return Object containing the key and salt used
     */
    public static KeyWithSalt deriveKeyWithSalt(char[] password) {
        byte[] salt = generateRandomBytes(SALT_SIZE);
        SecretKey key = deriveKey(password, salt);
        return new KeyWithSalt(key, salt);
    }

    /**
     * Generate cryptographically secure random bytes.
     *
     * @param length The number of bytes to generate
     * @return Random bytes
     */
    public static byte[] generateRandomBytes(int length) {
        byte[] bytes = new byte[length];
        new SecureRandom().nextBytes(bytes);
        return bytes;
    }

    /**
     * Securely wipe a byte array by zeroing it.
     *
     * @param data The data to wipe
     */
    public static void wipe(byte[] data) {
        if (data != null) {
            Arrays.fill(data, (byte) 0);
        }
    }

    /**
     * Securely wipe a char array by zeroing it.
     *
     * @param data The data to wipe
     */
    public static void wipe(char[] data) {
        if (data != null) {
            Arrays.fill(data, '\0');
        }
    }

    /**
     * Convert char array to byte array using UTF-8 encoding.
     *
     * @param chars The character array
     * @return The byte array
     */
    private static byte[] charsToBytes(char[] chars) {
        CharBuffer charBuffer = CharBuffer.wrap(chars);
        ByteBuffer byteBuffer = StandardCharsets.UTF_8.encode(charBuffer);
        byte[] bytes = Arrays.copyOfRange(byteBuffer.array(), byteBuffer.position(), byteBuffer.limit());
        wipe(byteBuffer.array());
        return bytes;
    }

    /**
     * Holder for key and salt pair.
     */
    public static class KeyWithSalt {
        private final SecretKey key;
        private final byte[] salt;

        public KeyWithSalt(SecretKey key, byte[] salt) {
            this.key = key;
            this.salt = salt;
        }

        public SecretKey getKey() {
            return key;
        }

        public byte[] getSalt() {
            return salt;
        }
    }
}
