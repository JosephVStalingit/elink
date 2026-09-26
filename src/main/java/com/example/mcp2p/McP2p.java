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
 * 模组总入口，同时也存放整个模组的"全局单例"。
 *
 * <p>这里做的事情很少，因为启动阶段不该做重活：读取配置、读出安装 ID、探测 WebRTC 原生库能不能用，
 * 然后注册 {@code /mcp2p} 命令。**只有配置里写了 auto-join，或玩家主动执行命令时**，才会真正连上
 * MQTT 并建立 P2P 连接。
 *
 * <p>三种加载器的入口形式不同（Fabric 用 {@code ModInitializer} 接口，Forge / NeoForge 用 {@code @Mod}
 * 注解加构造函数），下面用 Stonecutter 条件注释区分开，但三者最终都调用同一个 {@link #start()}。
 * 想支持新的加载器时，只要在这些条件块里再补一个分支。
 *
 * <p>线程说明：{@link #start()} 由加载器调用一次；其余 public 方法可能被命令（游戏主线程）或后台线程
 * 调用，所以它们要么是 {@code synchronized}，要么内部只做很轻的操作。
 */
/*? if forge {*/
/*@Mod(McP2p.MOD_ID)
*//*?}*/
/*? if neoforge {*/
/*@Mod(McP2p.MOD_ID)
*//*?}*/
public class McP2p /*? if fabric {*/ implements ModInitializer /*?}*/ {
    /** 模组 ID，必须与 {@code fabric.mod.json} / {@code mods.toml} 里的 id 完全相同。 */
    public static final String MOD_ID = "mcp2p";

    /** 全模组共用的日志器，日志里会以模块名（例如 mcp2p/tunnel）打标签，方便定位问题。 */
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    /**
     * 「对局域网开放」端口的提供者。
     *
     * <p>这个端口只存在于客户端进程，所以默认实现返回 -1（表示"没有"），由客户端侧的
     * {@code McP2pClient.install()} 在运行时替换成真正的实现。这样专用服务器永远不会去碰客户端类。
     */
    private static volatile IntSupplier lanPortProvider = () -> -1;

    /** 已加载的配置对象（可以改值并写回文件）。 */
    private static McP2pConfig config;

    /** 本安装固定的 ID，用来在对端之间区分"我"和"别人"（重启后不变）。 */
    private static String installId;

    /** 配置目录，由加载器提供（每个游戏实例不一样）。 */
    private static Path configDir;

    /** 当前的信令会话；为 null 表示还没加入任何房间。 */
    private static P2PSession session;

    /** 隧道：负责把 TCP 流量塞进数据通道，或把数据通道里的流量还原成 TCP。 */
    private static TunnelSession tunnel;

    /** 局域网广播器（只有客户端用），让朋友能在多人游戏列表里看到本机隧道。 */
    private static LanAnnouncer lanAnnouncer;

    /** 账号服务：LittleSkin 登录、令牌刷新、账号挑战应答。 */
    private static IdentityService identity;

    // ------------------------------------------------------------------
    // 加载器入口：三种加载器的入口写法不同，但都只做一件事——调用 start()
    // 条件块内部只能用 // 行注释，因为整块在其它加载器节点里是"被注释的代码"
    // ------------------------------------------------------------------

    /*? if fabric {*/
    /** Fabric 入口，游戏启动时加载器会调用一次。 */
    @Override
    public void onInitialize() {
        start();
    }
    /*?}*/

    /*? if forge {*/
    /*// Forge 入口：@Mod 标注的类，加载器把上下文注入构造函数
    public McP2p(final FMLJavaModLoadingContext context) {
        start();
    }
    *//*?}*/

    /*? if neoforge {*/
    /*// NeoForge 入口：@Mod 标注的类，构造函数会拿到 mod 事件总线
    public McP2p(final IEventBus modEventBus) {
        start();
    }
    *//*?}*/

    /**
     * 启动阶段真正要做的事，三种加载器入口最终都走到这里。
     *
     * <p>顺序有讲究：先把"基础设施"准备好（配置、安装 ID、隧道、账号服务），再注册命令（玩家随时可能
     * 敲命令），然后打印信息、探测 WebRTC，最后才做可选的自动动作。
     */
    private static void start() {
        // 1) 配置与身份。这一步不联网、不阻塞：读文件，没有就写一份默认的
        configDir = Platform.configDir();
        config = McP2pConfig.load(configDir);
        installId = InstallId.loadOrCreate(configDir); // 首次运行生成 UUID 并落盘，重启后不变
        config.ensureRoomCode(); // 还没有房间码就生成一个，例如 MCP2P-7F3A9C

        // 2) 隧道与账号服务只是"造对象"，此时不监听端口、也不发任何请求
        tunnel = new TunnelSession();
        identity = new IdentityService(config, configDir);

        // 3) 注册 /mcp2p 命令。各加载器的注册时机不同，差异藏在 Platform 里
        McP2pCommand.register();

        LOGGER.info(
                "MC P2P {} is initialising for Minecraft {}",
                modVersion(),
                SharedConstants.getCurrentVersion().getName());
        LOGGER.info(
                "Signalling broker {}, room {}", config.getBrokerUri(), config.getRoomCode());

        // 4) 探测 WebRTC 原生库。就算失败也不影响游戏启动，只是无法使用 P2P
        if (WebRtcEngine.loadNativeLibrary()) {
            LOGGER.info("The native WebRTC library is ready, P2P connections are available");
        } else {
            LOGGER.warn("WebRTC is unavailable on this platform, P2P connections are disabled");
        }

        // 5) 配置过账号时，后台刷新一次令牌，顺便拿到权威用户名（失败只记日志，不打断启动）
        if (identity.hasAccount()) {
            identity.resolveAsync(message -> LOGGER.info("{}", message));
        }

        // 6) 只有客户端才有"多人游戏列表"，也才有可共享的集成服务器
        if (Platform.isClient()) {
            McP2pClient.install(); // 把"对局域网开放"的端口接到 lanPortProvider
            startLanAnnouncer(); // 开始周期性广播，让朋友能在列表里看到
        }

        // 7) 服务端/自动化场景：开服即加入房间并自动扮演角色，见 autoStart()
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
     * 确保已经加入某个房间（幂等：已经在房间里就直接返回 true）。
     *
     * <p>这是"把玩家的一句话变成一个 P2P 会话"的关键一步：先确认 WebRTC 可用，再创建
     * {@link P2PSession}（它会连 MQTT 并广播 hello），最后把会话交给隧道，隧道才知道往哪里发帧。
     *
     * <p>注意：连接是异步的，本方法返回时只是"请求已发出"，真正的连接结果看日志或
     * {@code P2PSession} 的回调。
     *
     * @param room 房间码（决定 MQTT 主题）
     * @return {@code false} 表示 WebRTC 不可用或 broker 连接请求失败
     */
    public static synchronized boolean ensureInRoom(final String room) {
        if (session != null) {
            return true; // 已经在一个房间里了，不重复加入
        }
        if (!WebRtcEngine.loadNativeLibrary()) {
            LOGGER.warn("Cannot join a room: the native WebRTC library is unavailable");
            return false;
        }

        config.setRoomCode(room);
        final P2PSession newSession =
                new P2PSession(config, installId, displayName(), identity, tunnel);
        // 先把隧道与会话互相接上：隧道负责发帧，会话把收到的消息回调给隧道
        tunnel.attach(newSession);
        try {
            newSession.join(); // 连接请求，不等结果
        } catch (MqttException e) {
            LOGGER.error("Could not join the room {}", room, e);
            return false;
        }

        session = newSession;
        return true;
    }

    /**
     * 把自己这边的一个本地游戏端口共享给房间里的所有人（托管方）。
     *
     * <p>端口来源有两种：配置里显式写了 {@code forward-port}（专用服务器常用），或者客户端里
     * "对局域网开放"后拿到的端口。两者都拿不到时返回一句给玩家看的提示。
     *
     * @return 给玩家看的文字（成功或失败原因）
     */
    public static synchronized String startHosting() {
        final int port = config.getForwardPort() > 0 ? config.getForwardPort() : lanPort();
        if (port <= 0) {
            return "Open this world to LAN first, or set forward-port in config/mcp2p.properties";
        }

        final String result = tunnel.startHost(port);
        if (session != null && tunnel.isHosting()) {
            // 顺便告诉房间"我这里可以进"，加入方会优先挑这样的对端
            session.setHosting(true);
        }
        return result;
    }

    /**
     * 在本机开一个监听端口，把它接进房间（加入方）。配置里 {@code local-port} 为 0 时，
     * 由系统自动挑一个空闲端口，实际端口会通过局域网广播告诉游戏客户端。
     *
     * @return 给玩家看的文字（含实际监听端口）
     */
    public static synchronized String startJoining() {
        final String result = tunnel.startGuest(config.getLocalPort());
        if (session != null) {
            session.setHosting(false); // 明确声明"我这边没有可共享的世界"
        }
        return result;
    }

    /** 离开房间：关闭所有对端连接，并清理会话。隧道本身由调用方（命令）先停。 */
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

