package com.example.elink.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Per-installation identifier.
 *
 * <p>Peers recognise each other by this value, so it has to survive restarts: a fresh random id on
 * every start would look like a new player joining the room. It is not a secret and not a proof of
 * identity — account verification belongs to a later batch.
 */
public final class InstallId {
    private static final Logger LOGGER = LoggerFactory.getLogger("elink/config");

    private static final String FILE_NAME = "elink-id.txt";

    private InstallId() {}

    /** Reads the id from the config directory, creating one on first use. */
    public static String loadOrCreate(final Path configDir) {
        final Path file = configDir.resolve(FILE_NAME);

        try {
            if (Files.isRegularFile(file)) {
                final String id = Files.readString(file, StandardCharsets.UTF_8).trim();
                if (!id.isEmpty()) {
                    return id;
                }
            }

            Files.createDirectories(configDir);
            final String id = UUID.randomUUID().toString();
            Files.writeString(file, id + System.lineSeparator(), StandardCharsets.UTF_8);
            LOGGER.info("Created a new installation id in {}", file);
            return id;
        } catch (IOException e) {
            // A non-persistent id still works, it just means peers see a new player after a restart.
            LOGGER.warn("Could not read or write {}, using a temporary id", file, e);
            return UUID.randomUUID().toString();
        }
    }
}
