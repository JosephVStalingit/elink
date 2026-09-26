package com.example.mcp2p.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Random;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Persistent settings of MC P2P, stored as a properties file in the game's config directory.
 *
 * <p>The class deliberately avoids Minecraft and Fabric types so that the P2P stack can also be
 * exercised outside the game, and so that it behaves identically on client and dedicated server.
 */
public final class McP2pConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger("mcp2p/config");

    /** Name of the file inside the config directory. */
    public static final String FILE_NAME = "mcp2p.properties";

    /** Public broker used while the user has not configured their own. */
    public static final String DEFAULT_BROKER_URI = "tcp://broker.emqx.io:1883";

    public static final String DEFAULT_TOPIC_PREFIX = "mcp2p";
    public static final String DEFAULT_STUN_SERVERS = "stun:stun.l.google.com:19302";
    public static final String DEFAULT_DATA_CHANNEL_LABEL = "mcp2p";

    /**
     * Local port a joining player connects to. {@code 0} means "let the operating system pick a free
     * port", which is the default: nothing is hard coded, and a port that happens to be taken by
     * another program cannot prevent joining.
     */
    public static final int DEFAULT_LOCAL_PORT = 0;

    /** How long a successful account proof is reused before asking the account service again. */
    public static final int DEFAULT_CACHE_MINUTES = 10;

    /**
     * LittleSkin's Yggdrasil endpoint. Every call is built by appending one of the documented paths,
     * for example {@code /authserver/refresh} or {@code /sessionserver/session/minecraft/hasJoined}.
     */
    public static final String DEFAULT_LITTLESKIN_URL = "https://littleskin.cn/api/yggdrasil";

    /** Characters used for room codes: no letters or digits that are easy to mix up. */
    private static final String ROOM_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private static final int ROOM_CODE_LENGTH = 6;

    private String brokerUri = DEFAULT_BROKER_URI;
    private String topicPrefix = DEFAULT_TOPIC_PREFIX;
    private String roomCode = "";
    private String stunServers = DEFAULT_STUN_SERVERS;
    private String dataChannelLabel = DEFAULT_DATA_CHANNEL_LABEL;
    private SecretStore secrets;
    private String roomSecret = "";
    private String littleSkinUrl = DEFAULT_LITTLESKIN_URL;
    private String accessToken = "";
    private String clientToken = "";
    private String friends = "";
    private int localPort = DEFAULT_LOCAL_PORT;
    private int forwardPort;
    private int cacheMinutes = DEFAULT_CACHE_MINUTES;
    private boolean autoJoin;

    /**
     * Reads the configuration, writing a file with the defaults when there is none yet.
     *
     * @param configDir directory that holds the game's configuration files
     * @return the loaded configuration, never {@code null}
     */
    public static McP2pConfig load(final Path configDir) {
        final McP2pConfig config = new McP2pConfig();
        final Path file = configDir.resolve(FILE_NAME);
        config.openSecrets(configDir);

        if (!Files.isRegularFile(file)) {
            LOGGER.info("No {} yet, writing the defaults", file);
            config.save(configDir);
            return config;
        }

        final Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            properties.load(in);
        } catch (IOException e) {
            LOGGER.warn("Could not read {}, falling back to the defaults", file, e);
            return config;
        }

        config.brokerUri = properties.getProperty("broker", config.brokerUri).trim();
        config.topicPrefix = properties.getProperty("topic-prefix", config.topicPrefix).trim();
        config.roomCode = properties.getProperty("room", config.roomCode).trim();
        config.stunServers = properties.getProperty("stun-servers", config.stunServers).trim();
        config.dataChannelLabel =
                properties.getProperty("data-channel", config.dataChannelLabel).trim();
        config.roomSecret = properties.getProperty("room-secret", config.roomSecret).trim();
        config.littleSkinUrl = properties.getProperty("littleskin-url", config.littleSkinUrl).trim();
        config.friends = properties.getProperty("friends", config.friends).trim();
        config.loadTokens(properties, configDir);
        config.localPort = readInt(properties, "local-port", config.localPort);
        config.forwardPort = readInt(properties, "forward-port", config.forwardPort);
        config.cacheMinutes = readInt(properties, "identity-cache-minutes", config.cacheMinutes);
        config.autoJoin = Boolean.parseBoolean(properties.getProperty("auto-join", "false").trim());

        return config;
    }

    private static int readInt(final Properties properties, final String key, final int fallback) {
        try {
            return Integer.parseInt(properties.getProperty(key, Integer.toString(fallback)).trim());
        } catch (NumberFormatException e) {
            LOGGER.warn("{} is not a number, keeping {}", key, fallback);
            return fallback;
        }
    }

    /** Writes the current values back to the configuration file. */
    public void save(final Path configDir) {
        final Properties properties = new Properties();
        properties.setProperty("broker", brokerUri);
        properties.setProperty("topic-prefix", topicPrefix);
        properties.setProperty("room", roomCode);
        properties.setProperty("stun-servers", stunServers);
        properties.setProperty("data-channel", dataChannelLabel);
        properties.setProperty("room-secret", roomSecret);
        properties.setProperty("littleskin-url", littleSkinUrl);
        properties.setProperty("friends", friends);
        // Tokens never go into this readable file; they live in the encrypted store beside it.
        properties.setProperty("access-token", "");
        properties.setProperty("client-token", "");
        if (secrets != null) {
            secrets.put("access-token", accessToken);
            secrets.put("client-token", clientToken);
        }
        properties.setProperty("local-port", Integer.toString(localPort));
        properties.setProperty("forward-port", Integer.toString(forwardPort));
        properties.setProperty("identity-cache-minutes", Integer.toString(cacheMinutes));
        properties.setProperty("auto-join", Boolean.toString(autoJoin));

        try {
            Files.createDirectories(configDir);
            final Path file = configDir.resolve(FILE_NAME);
            try (OutputStream out = Files.newOutputStream(file)) {
                properties.store(out, "MC P2P settings");
            }
        } catch (IOException e) {
            LOGGER.warn("Could not write the MC P2P configuration", e);
        }
    }

    /** Opens, or creates, the encrypted store the account tokens live in. */
    private void openSecrets(final Path configDir) {
        try {
            secrets = SecretStore.open(configDir);
        } catch (IOException e) {
            LOGGER.warn("Could not open the secret store, the account tokens cannot be stored", e);
            secrets = null;
        }
    }

    /**
     * Reads the account tokens from the encrypted store. Tokens that are still in the properties file
     * (written by an older build) are moved over and the file is rewritten without them.
     */
    private void loadTokens(final Properties properties, final Path configDir) {
        final String plainAccess = properties.getProperty("access-token", "").trim();
        final String plainClient = properties.getProperty("client-token", "").trim();

        if (secrets == null) {
            accessToken = plainAccess;
            clientToken = plainClient;
            return;
        }

        accessToken = secrets.get("access-token").orElse(plainAccess);
        clientToken = secrets.get("client-token").orElse(plainClient);

        if (!plainAccess.isBlank() || !plainClient.isBlank()) {
            LOGGER.info("Moving the account tokens out of the readable configuration file");
            save(configDir);
        }
    }

    /** @return {@code true} when the account tokens are kept in the encrypted store */
    public boolean hasSecretStore() {
        return secrets != null;
    }

    /** @return {@code true} when the master key of that store is protected by the operating system */
    public boolean isMasterKeyProtected() {
        return secrets != null && secrets.isOsProtected();
    }

    /** @return a fresh room code such as {@code MCP2P-7F3A9C} */
    public static String generateRoomCode() {
        final Random random = new Random();
        final StringBuilder code = new StringBuilder("MCP2P-");
        for (int i = 0; i < ROOM_CODE_LENGTH; i++) {
            code.append(ROOM_ALPHABET.charAt(random.nextInt(ROOM_ALPHABET.length())));
        }
        return code.toString();
    }

    /** @return the room code, generating one when the user has not chosen a room yet */
    public String ensureRoomCode() {
        if (roomCode == null || roomCode.isBlank()) {
            roomCode = generateRoomCode();
        }
        return roomCode;
    }

    /** MQTT topic that carries the offers, answers and ICE candidates of the room. */
    public String roomTopic() {
        return topicPrefix + "/" + roomCode;
    }

    /** MQTT topic that carries the join and leave announcements of the room. */
    public String presenceTopic() {
        return roomTopic() + "/presence";
    }

    /** @return the configured STUN/TURN server URIs, split on commas, semicolons or spaces */
    public List<String> iceServerUrls() {
        final List<String> urls = new ArrayList<>();
        for (String url : stunServers.split("[,;\\s]+")) {
            if (!url.isBlank()) {
                urls.add(url.trim());
            }
        }
        return urls;
    }

    public String getBrokerUri() {
        return brokerUri;
    }

    public void setBrokerUri(final String brokerUri) {
        this.brokerUri = brokerUri;
    }

    public String getTopicPrefix() {
        return topicPrefix;
    }

    public void setTopicPrefix(final String topicPrefix) {
        this.topicPrefix = topicPrefix;
    }

    public String getRoomCode() {
        return roomCode;
    }

    public void setRoomCode(final String roomCode) {
        this.roomCode = roomCode;
    }

    public String getStunServers() {
        return stunServers;
    }

    public void setStunServers(final String stunServers) {
        this.stunServers = stunServers;
    }

    public String getDataChannelLabel() {
        return dataChannelLabel;
    }

    public void setDataChannelLabel(final String dataChannelLabel) {
        this.dataChannelLabel = dataChannelLabel;
    }

    public boolean isAutoJoin() {
        return autoJoin;
    }

    public void setAutoJoin(final boolean autoJoin) {
        this.autoJoin = autoJoin;
    }

    /**
     * @return the secret peers have to prove they know before a session is negotiated; an empty value
     *     means the room is open to anybody who knows its code
     */
    public String getRoomSecret() {
        return roomSecret;
    }

    public void setRoomSecret(final String roomSecret) {
        this.roomSecret = roomSecret;
    }

    /** @return the Yggdrasil endpoint of the account service in use */
    public String getLittleSkinUrl() {
        return littleSkinUrl;
    }

    public void setLittleSkinUrl(final String littleSkinUrl) {
        this.littleSkinUrl = littleSkinUrl;
    }

    /** @return the access token of the account, or an empty string while none is configured */
    public String getAccessToken() {
        return accessToken;
    }

    /** @return the client token that belongs to {@link #getAccessToken()} */
    public String getClientToken() {
        return clientToken;
    }

    /** Stores a fresh token pair, as handed out by LittleSkin. */
    public void setTokens(final String accessToken, final String clientToken) {
        this.accessToken = accessToken == null ? "" : accessToken;
        this.clientToken = clientToken == null ? "" : clientToken;
    }

    /** @return {@code true} when an account is configured, so an identity can be resolved */
    public boolean hasAccount() {
        return !accessToken.isBlank();
    }

    /** @return the names or uuids that may join, separated by commas */
    public String getFriends() {
        return friends;
    }

    public void setFriends(final String friends) {
        this.friends = friends;
    }

    /** @return the allow list in lower case; an empty list means "anybody with the room key" */
    public Set<String> friendList() {
        final Set<String> list = new LinkedHashSet<>();
        for (String entry : friends.split("[,;\\s]+")) {
            if (!entry.isBlank()) {
                list.add(entry.trim().toLowerCase(Locale.ROOT));
            }
        }
        return list;
    }

    /** @return the local port a joining player connects to, or {@code 0} to pick a free one */
    public int getLocalPort() {
        return localPort;
    }

    public void setLocalPort(final int localPort) {
        this.localPort = localPort;
    }

    /**
     * @return the game port to share while hosting, or {@code 0} to use the port of the "Open to LAN"
     *     world. Set it on a dedicated server, where there is no integrated server to ask.
     */
    public int getForwardPort() {
        return forwardPort;
    }

    public void setForwardPort(final int forwardPort) {
        this.forwardPort = forwardPort;
    }

    /**
     * @return how long a successful account proof is reused, in minutes, before the account service is
     *     asked again; {@code 0} switches the cache off
     */
    public int getCacheMinutes() {
        return cacheMinutes;
    }

    public void setCacheMinutes(final int cacheMinutes) {
        this.cacheMinutes = cacheMinutes;
    }
}
