package com.example.elink.identity;

import com.example.elink.config.ELinkConfig;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This installation's account identity, and the two questions a room asks about identities.
 *
 * <p>Everything here talks to the network, so every call runs on one background thread and reports
 * back through a callback. Nothing may be called from the game thread expecting a result.
 *
 * <p>The joiner side answers a host challenge with {@code join}: only the owner of the account can do
 * that, because it needs the secret token. The host side then asks {@code hasJoined}, which is what
 * turns "I am Xyz" into "Xyz proved it".
 */
public final class IdentityService {
    private static final Logger LOGGER = LoggerFactory.getLogger("elink/identity");

    private final ELinkConfig config;
    private final Path configDir;
    private final LittleSkinClient client;
    private final ExecutorService worker =
            Executors.newSingleThreadExecutor(
                    runnable -> {
                        final Thread thread = new Thread(runnable, "elink-identity");
                        thread.setDaemon(true);
                        return thread;
                    });

    private final AtomicReference<PlayerIdentity> identity = new AtomicReference<>();
    private final AtomicReference<String> lastError = new AtomicReference<>();
    /** Recent account proofs, so a peer that comes back does not cost another service call. */
    private final ResultCache<String, PlayerIdentity> proofs;

    public IdentityService(final ELinkConfig config, final Path configDir) {
        this.config = config;
        this.configDir = configDir;
        this.client = new LittleSkinClient(config.getLittleSkinUrl());
        this.proofs =
                new ResultCache<>(Duration.ofMinutes(Math.max(0, config.getCacheMinutes())), 256);
    }

    /** @return how many account proofs are currently reused */
    public int cachedProofs() {
        return proofs.size();
    }

    /** @return the identity that was resolved from the account service, if any */
    public Optional<PlayerIdentity> identity() {
        return Optional.ofNullable(identity.get());
    }

    /** @return the reason the last account call failed, if it did */
    public Optional<String> lastError() {
        return Optional.ofNullable(lastError.get());
    }

    /** @return {@code true} when a token pair is configured */
    public boolean hasAccount() {
        return config.hasAccount();
    }

    /** @return the endpoint in use, for status output */
    public String serviceUrl() {
        return client.baseUrl();
    }

    /** Shuts the background thread down. */
    public void close() {
        worker.shutdownNow();
    }

    /**
     * Logs in with a name and password and keeps the resulting token pair.
     *
     * @param onResult receives a message meant for the player, on the background thread
     */
    public void loginAsync(final String name, final String password, final Consumer<String> onResult) {
        worker.execute(
                () -> {
                    try {
                        final LittleSkinClient.TokenPair pair =
                                client.authenticate(name, password, config.getClientToken());
                        if (pair.accessToken() == null) {
                            report(onResult, "The account service returned no access token");
                            return;
                        }
                        adopt(pair);
                        report(onResult, "Logged in as " + describe(pair.identity()));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } catch (Exception e) {
                        lastError.set(describe(e));
                        report(onResult, "Login failed: " + describe(e));
                    }
                });
    }

    /**
     * Resolves the identity behind the configured token pair. A stored access token goes stale, so the
     * refresh doubles as the check and as the way to keep the stored pair current.
     */
    public void resolveAsync(final Consumer<String> onResult) {
        if (!config.hasAccount()) {
            report(onResult, "No account configured yet, use /elink login <email> <password>");
            return;
        }

        worker.execute(
                () -> {
                    try {
                        final LittleSkinClient.TokenPair pair =
                                client.refresh(config.getAccessToken(), config.getClientToken());
                        adopt(pair);
                        report(onResult, "Signed in as " + describe(pair.identity()));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } catch (Exception e) {
                        lastError.set(describe(e));
                        report(onResult, "Could not use the stored account: " + describe(e));
                    }
                });
    }

    /**
     * Answers a host challenge. This is the joining half of the proof: the account service only accepts
     * it from somebody who holds the account's token.
     *
     * @param serverId the id the host handed out for this handshake
     * @param onResult receives a message on failure; nothing on success
     */
    public void answerChallengeAsync(final String serverId, final Consumer<String> onResult) {
        worker.execute(
                () -> {
                    try {
                        PlayerIdentity current = identity.get();
                        if (current == null) {
                            final LittleSkinClient.TokenPair pair =
                                    client.refresh(config.getAccessToken(), config.getClientToken());
                            adopt(pair);
                            current = pair.identity();
                        }
                        if (current == null) {
                            report(onResult, "The account has no player profile selected");
                            return;
                        }

                        client.join(config.getAccessToken(), current.uuid(), serverId);
                        LOGGER.info("Answered the account challenge as {}", current.name());
                        report(onResult, null);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } catch (Exception e) {
                        lastError.set(describe(e));
                        LOGGER.warn("Could not answer the account challenge: {}", describe(e));
                        report(onResult, "Could not answer the account challenge: " + describe(e));
                    }
                });
    }

    /**
     * The hosting half of the proof: asks the account service whether an account really joined with the
     * id that was handed out.
     */
    public void verifyAsync(
            final String username,
            final String serverId,
            final Consumer<Optional<PlayerIdentity>> onResult) {
        final String key = username == null ? "" : username.toLowerCase(Locale.ROOT);
        final Optional<PlayerIdentity> cached = proofs.get(key);
        if (cached.isPresent()) {
            LOGGER.debug("Reusing the recent account proof of {}", username);
            onResult.accept(cached);
            return;
        }

        worker.execute(
                () -> {
                    try {
                        final Optional<PlayerIdentity> verified = client.hasJoined(username, serverId);
                        verified.ifPresent(account -> proofs.put(key, account));
                        onResult.accept(verified);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        onResult.accept(Optional.empty());
                    } catch (Exception e) {
                        lastError.set(describe(e));
                        LOGGER.warn("Verifying {} failed: {}", username, describe(e));
                        onResult.accept(Optional.empty());
                    }
                });
    }

    /** Keeps a fresh token pair and identity, and writes both back to the configuration. */
    private void adopt(final LittleSkinClient.TokenPair pair) {
        if (pair.accessToken() != null) {
            config.setTokens(pair.accessToken(), pair.clientToken());
            config.save(configDir);
        }
        if (pair.identity() != null) {
            identity.set(pair.identity());
            lastError.set(null);
        }
    }

    private static String describe(final PlayerIdentity identity) {
        return identity == null
                ? "an unknown account"
                : identity.name() + " (" + identity.dashedUuid() + ")";
    }

    private static String describe(final Exception e) {
        if (e instanceof LittleSkinClient.LittleSkinException littleSkin) {
            return littleSkin.getMessage()
                    + (littleSkin.isInvalidToken() ? ", please log in again" : "");
        }
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }

    private static void report(final Consumer<String> onResult, final String message) {
        if (onResult != null && message != null) {
            onResult.accept(message);
        }
    }
}
