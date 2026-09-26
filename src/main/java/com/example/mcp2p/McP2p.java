package com.example.mcp2p;

import com.example.mcp2p.client.McP2pClient;
import com.example.mcp2p.command.McP2pCommand;
import com.example.mcp2p.config.InstallId;
import com.example.mcp2p.config.McP2pConfig;
import com.example.mcp2p.identity.IdentityService;
import com.example.mcp2p.identity.PlayerIdentity;
import com.example.mcp2p.lan.LanAnnouncer;
import com.example.mcp2p.net.P2PSession;
import com.example.mcp2p.platform.Platform;
import com.example.mcp2p.rtc.WebRtcEngine;
import com.example.mcp2p.tunnel.TunnelSession;
import java.io.IOException;
import java.nio.file.Path;
import java.util.function.IntSupplier;
import net.minecraft.SharedConstants;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/*? if fabric {*/
import net.fabricmc.api.ModInitializer;
/*?}*/

/*? if forge {*/
/*import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
*//*?}*/

/*? if neoforge {*/
/*import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
*//*?}*/

/**
 * Common entrypoint of MC P2P.
 *
 * <p>Startup stays cheap and offline: the configuration is loaded, the installation id is read and
 * the native WebRTC library is probed, but neither a broker connection nor a peer connection is
 * opened unless the configuration asks for it. The {@code /mcp2p} command is registered here, which
 * is how a player starts sharing or joining a world.
 */
/*? if forge {*/
/*@Mod(McP2p.MOD_ID)
*//*?}*/
/*? if neoforge {*/
/*@Mod(McP2p.MOD_ID)
*//*?}*/
public class McP2p /*? if fabric {*/ implements ModInitializer /*?}*/ {
    public static final String MOD_ID = "mcp2p";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    /** Answers the "open to LAN" port; only a client installs a provider. */
    private static volatile IntSupplier lanPortProvider = () -> -1;

    private static McP2pConfig config;
    private static String installId;
    private static Path configDir;
    private static P2PSession session;
    private static TunnelSession tunnel;
    private static LanAnnouncer lanAnnouncer;
    private static IdentityService identity;

    /*? if fabric {*/
    @Override
    public void onInitialize() {
        start();
    }
    /*?}*/

    /*? if forge {*/
    /*public McP2p(final FMLJavaModLoadingContext context) {
        start();
    }
    *//*?}*/

    /*? if neoforge {*/
    /*public McP2p(final IEventBus modEventBus) {
        start();
    }
    *//*?}*/

    /** Everything this mod does at startup, shared by the loader entrypoints. */
    private static void start() {
        configDir = Platform.configDir();
        config = McP2pConfig.load(configDir);
        installId = InstallId.loadOrCreate(configDir);
        config.ensureRoomCode();
        tunnel = new TunnelSession();
        identity = new IdentityService(config, configDir);

        McP2pCommand.register();

        LOGGER.info(
                "MC P2P {} is initialising for Minecraft {}",
                modVersion(),
                SharedConstants.getCurrentVersion().getName());
        LOGGER.info(
                "Signalling broker {}, room {}", config.getBrokerUri(), config.getRoomCode());

        if (WebRtcEngine.loadNativeLibrary()) {
            LOGGER.info("The native WebRTC library is ready, P2P connections are available");
        } else {
            LOGGER.warn("WebRTC is unavailable on this platform, P2P connections are disabled");
        }

        if (identity.hasAccount()) {
            identity.resolveAsync(message -> LOGGER.info("{}", message));
        }

        if (Platform.isClient()) {
            McP2pClient.install();
            startLanAnnouncer();
        }

        if (config.isAutoJoin()) {
            autoStart();
        }
    }

    /**
     * Advertises the tunnel in the multiplayer list. Only a client has a server list to advertise to,
     * so a dedicated server never gets here.
     */
    private static void startLanAnnouncer() {
        try {
            lanAnnouncer = new LanAnnouncer();
            lanAnnouncer.start(
                    () -> tunnel == null ? 0 : tunnel.localPort(), McP2p::announceMotd);
        } catch (IOException e) {
            LOGGER.warn("Could not announce the tunnel in the multiplayer list", e);
        }
    }

    private static String announceMotd() {
        final String room = config == null ? "" : config.getRoomCode();
        final P2PSession current = session;
        if (current == null) {
            return "MC P2P " + room;
        }
        final String host = current.hostingPeerName();
        return host == null ? "MC P2P " + room + " (waiting for the host)" : "MC P2P " + room + " - " + host;
    }

    /**
     * Joins the configured room and immediately takes a role, for setups that should always be online:
     * a dedicated server with {@code forward-port} shares that port, anything else opens the local
     * port for joining.
     */
    private static void autoStart() {
        if (!ensureInRoom(config.getRoomCode())) {
            return;
        }
        if (config.getForwardPort() > 0) {
            LOGGER.info("{}", startHosting());
        } else {
            LOGGER.info("{}", startJoining());
        }
    }

    /**
     * Joins a room, announcing this peer to everyone already in it. Connecting is asynchronous: this
     * method returns as soon as the broker connection has been requested.
     *
     * @return {@code false} when WebRTC is unavailable or the broker could not be reached
     */
    public static synchronized boolean ensureInRoom(final String room) {
        if (session != null) {
            return true;
        }
        if (!WebRtcEngine.loadNativeLibrary()) {
            LOGGER.warn("Cannot join a room: the native WebRTC library is unavailable");
            return false;
        }

        config.setRoomCode(room);
        final P2PSession newSession =
                new P2PSession(config, installId, displayName(), identity, tunnel);
        tunnel.attach(newSession);
        try {
            newSession.join();
        } catch (MqttException e) {
            LOGGER.error("Could not join the room {}", room, e);
            return false;
        }

        session = newSession;
        return true;
    }

    /**
     * Shares a local game port with the room, so friends can join the world.
     *
     * @return a message for the player, either the result or the reason it failed
     */
    public static synchronized String startHosting() {
        final int port = config.getForwardPort() > 0 ? config.getForwardPort() : lanPort();
        if (port <= 0) {
            return "Open this world to LAN first, or set forward-port in config/mcp2p.properties";
        }

        final String result = tunnel.startHost(port);
        if (session != null && tunnel.isHosting()) {
            session.setHosting(true);
        }
        return result;
    }

    /**
     * Tunnels a local port into the room; players enter it as "Direct connect" address.
     *
     * @return a message for the player, either the result or the reason it failed
     */
    public static synchronized String startJoining() {
        final String result = tunnel.startGuest(config.getLocalPort());
        if (session != null) {
            session.setHosting(false);
        }
        return result;
    }

    /** Leaves the room and closes every peer connection. */
    public static synchronized void leaveRoom() {
        if (session == null) {
            return;
        }
        session.setHosting(false);
        session.leave();
        session = null;
    }

    /** @return the tunnel of this process, ready to be given a role */
    public static TunnelSession tunnel() {
        return tunnel;
    }

    /** @return the configuration that was loaded during startup */
    public static McP2pConfig config() {
        return config;
    }

    /** @return the directory the configuration lives in */
    public static Path configDir() {
        return configDir;
    }

    /** @return the stable identifier of this installation */
    public static String installId() {
        return installId;
    }

    /** Sets the provider for the "open to LAN" port; called by the client entrypoint. */
    public static void setLanPortProvider(final IntSupplier provider) {
        lanPortProvider = provider;
    }

    /** @return the port of the shared world, or {@code -1} when there is none */
    public static int lanPort() {
        return lanPortProvider.getAsInt();
    }

    /**
     * @return the name other peers see: the account name when a LittleSkin account is configured,
     *     otherwise the operating system account as a stand-in
     */
    public static String displayName() {
        if (identity != null) {
            final String accountName = identity.identity().map(PlayerIdentity::name).orElse(null);
            if (accountName != null && !accountName.isBlank()) {
                return accountName;
            }
        }
        return System.getProperty("user.name", "player");
    }

    /** @return the account service of this installation */
    public static IdentityService identity() {
        return identity;
    }

    /** @return the version the active loader reports for this mod */
    private static String modVersion() {
        return Platform.modVersion(MOD_ID);
    }
}

