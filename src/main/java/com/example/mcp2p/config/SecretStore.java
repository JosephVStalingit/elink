package com.example.mcp2p.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;
import java.util.Properties;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Stores secrets — today the account tokens — encrypted, so that copying or sharing the configuration
 * file does not leak them.
 *
 * <p>Two files are involved. {@code mcp2p.key} holds the 256 bit master key, wrapped with the Windows
 * data protection API where that is available, so only the same Windows account can unwrap it.
 * {@code mcp2p.secrets} holds the values encrypted with AES-GCM under that master key.
 *
 * <p>Without DPAPI the master key is only base64 encoded, in a file next to the values. That still
 * keeps tokens out of the readable configuration file and out of any backup that does not include the
 * key file, but it is no defence against somebody who can read the whole directory. The startup log
 * line says which of the two is in use.
 */
public final class SecretStore {
    private static final Logger LOGGER = LoggerFactory.getLogger("mcp2p/config");

    private static final String KEY_FILE = "mcp2p.key";
    private static final String VALUES_FILE = "mcp2p.secrets";
    private static final int KEY_BYTES = 32;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final Path valuesFile;
    private final SecretKeySpec masterKey;
    private final boolean osProtected;

    private SecretStore(final Path valuesFile, final SecretKeySpec masterKey, final boolean osProtected) {
        this.valuesFile = valuesFile;
        this.masterKey = masterKey;
        this.osProtected = osProtected;
    }

    /**
     * Opens the store, creating a master key on first use.
     *
     * @throws IOException when the key file cannot be read or written
     */
    public static SecretStore open(final Path configDir) throws IOException {
        Files.createDirectories(configDir);
        final Path keyFile = configDir.resolve(KEY_FILE);
        final Path valuesFile = configDir.resolve(VALUES_FILE);

        byte[] key = null;
        boolean osProtected = false;

        if (Files.isRegularFile(keyFile)) {
            final byte[] stored = Base64.getDecoder().decode(Files.readString(keyFile, StandardCharsets.UTF_8).trim());
            if (WindowsKeyProtector.isAvailable()) {
                try {
                    key = WindowsKeyProtector.unprotect(stored);
                    osProtected = true;
                } catch (RuntimeException e) {
                    LOGGER.warn("Could not unwrap the master key, assuming it is stored as it is: {}", e.getMessage());
                }
            }
            if (key == null) {
                key = stored;
            }
        }

        if (key == null || key.length != KEY_BYTES) {
            key = new byte[KEY_BYTES];
            new SecureRandom().nextBytes(key);

            if (WindowsKeyProtector.isAvailable()) {
                try {
                    Files.writeString(
                            keyFile,
                            Base64.getEncoder().encodeToString(WindowsKeyProtector.protect(key))
                                    + System.lineSeparator(),
                            StandardCharsets.UTF_8);
                    osProtected = true;
                } catch (RuntimeException e) {
                    LOGGER.warn("Could not wrap the master key, storing it as it is: {}", e.getMessage());
                    Files.writeString(
                            keyFile,
                            Base64.getEncoder().encodeToString(key) + System.lineSeparator(),
                            StandardCharsets.UTF_8);
                }
            } else {
                Files.writeString(
                        keyFile,
                        Base64.getEncoder().encodeToString(key) + System.lineSeparator(),
                        StandardCharsets.UTF_8);
            }
            restrict(keyFile);
        }

        LOGGER.info(
                "Secrets are stored in {} ({})",
                VALUES_FILE,
                osProtected ? "master key protected by Windows DPAPI" : "master key in " + KEY_FILE);
        return new SecretStore(valuesFile, new SecretKeySpec(key, "AES"), osProtected);
    }

    /** @return {@code true} when the master key is wrapped with the platform key store */
    public boolean isOsProtected() {
        return osProtected;
    }

    /** @return the file the encrypted values live in */
    public Path valuesFile() {
        return valuesFile;
    }

    /** @return the decrypted value, or empty when there is none or it cannot be read */
    public Optional<String> get(final String name) {
        final String encoded = readValues().getProperty(name);
        if (encoded == null || encoded.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(decrypt(encoded));
        } catch (Exception e) {
            LOGGER.warn("Could not decrypt {}: {}", name, e.getMessage());
            return Optional.empty();
        }
    }

    /** Encrypts and stores a value; an empty value removes the entry. */
    public void put(final String name, final String value) {
        if (value == null || value.isBlank()) {
            remove(name);
            return;
        }

        try {
            final Properties values = readValues();
            values.setProperty(name, encrypt(value));
            writeValues(values);
        } catch (Exception e) {
            LOGGER.error("Could not store {}: {}", name, e.getMessage());
        }
    }

    /** Removes an entry. */
    public void remove(final String name) {
        final Properties values = readValues();
        if (values.remove(name) != null) {
            writeValues(values);
        }
    }

    private Properties readValues() {
        final Properties values = new Properties();
        if (!Files.isRegularFile(valuesFile)) {
            return values;
        }
        try (InputStream in = Files.newInputStream(valuesFile)) {
            values.load(in);
        } catch (IOException e) {
            LOGGER.warn("Could not read {}: {}", valuesFile, e.getMessage());
        }
        return values;
    }

    private void writeValues(final Properties values) {
        try {
            try (OutputStream out = Files.newOutputStream(valuesFile)) {
                values.store(out, "MC P2P secrets, encrypted with the master key in " + KEY_FILE);
            }
            restrict(valuesFile);
        } catch (IOException e) {
            LOGGER.error("Could not write {}: {}", valuesFile, e.getMessage());
        }
    }

    private String encrypt(final String value) throws Exception {
        final byte[] iv = new byte[IV_BYTES];
        new SecureRandom().nextBytes(iv);

        final Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, masterKey, new GCMParameterSpec(TAG_BITS, iv));
        final byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));

        final byte[] combined = new byte[iv.length + encrypted.length];
        System.arraycopy(iv, 0, combined, 0, iv.length);
        System.arraycopy(encrypted, 0, combined, iv.length, encrypted.length);
        return Base64.getEncoder().encodeToString(combined);
    }

    private String decrypt(final String encoded) throws Exception {
        final byte[] combined = Base64.getDecoder().decode(encoded);
        if (combined.length <= IV_BYTES) {
            throw new IllegalArgumentException("stored value is too short");
        }

        final byte[] iv = new byte[IV_BYTES];
        System.arraycopy(combined, 0, iv, 0, IV_BYTES);
        final byte[] encrypted = new byte[combined.length - IV_BYTES];
        System.arraycopy(combined, IV_BYTES, encrypted, 0, encrypted.length);

        final Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, masterKey, new GCMParameterSpec(TAG_BITS, iv));
        return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
    }

    /** Best effort at keeping the file to the current user; Windows may not honour every flag. */
    private static void restrict(final Path file) {
        try {
            file.toFile().setReadable(false, false);
            file.toFile().setReadable(true, true);
            file.toFile().setWritable(false, false);
            file.toFile().setWritable(true, true);
        } catch (RuntimeException e) {
            LOGGER.debug("Could not restrict the permissions of {}: {}", file, e.getMessage());
        }
    }
}
