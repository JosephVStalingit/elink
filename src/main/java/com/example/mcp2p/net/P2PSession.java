package com.example.mcp2p.net;

import com.example.mcp2p.config.McP2pConfig;
import com.example.mcp2p.identity.IdentityService;
import com.example.mcp2p.identity.PlayerIdentity;
import com.example.mcp2p.punch.PunchCoordinator;
import com.example.mcp2p.punch.UdpLink;
import com.example.mcp2p.rtc.PeerSession;
import com.example.mcp2p.rtc.WebRtcEngine;
import com.example.mcp2p.signalling.SignalMessage;
import com.example.mcp2p.signalling.RoomSecret;
import com.example.mcp2p.signalling.SignallingClient;
import dev.onvoid.webrtc.PortAllocatorConfig;
import dev.onvoid.webrtc.RTCConfiguration;
import dev.onvoid.webrtc.RTCIceCandidate;
import dev.onvoid.webrtc.RTCIceServer;
import dev.onvoid.webrtc.RTCIceTransportPolicy;
import dev.onvoid.webrtc.RTCPeerConnectionState;
import dev.onvoid.webrtc.RTCSdpType;
import dev.onvoid.webrtc.RTCSessionDescription;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 一个"房间"里的会话：加入 MQTT 房间，并为房间里的每个对端维护一条 {@link PeerSession}。
 *
 * <p>这个类是整个模组的**大脑**，也是最值得先读的类。它做四件事：
 *
 * <ol>
 *   <li><b>收发信令</b>：通过 {@link SignallingClient} 订阅房间主题，把收到的 JSON 变成
 *       {@link SignalMessage} 再分派给对应的处理方法；发出的每条消息都会自动带上房间密钥的证明。
 *   <li><b>决定谁先发起</b>：比较双方的安装 ID，字典序小的一方发 offer，另一方等。这样不会出现
 *       "双方同时发 offer"而互相打断。
 *   <li><b>账号证明状态机</b>：当房主设置了好友名单时，房主发 {@code challenge}，对方用令牌向账号服务
 *       证明后回 {@code challenge_response}，房主核实通过才发 {@code verified}；在此之前双方**不协商**。
 *   <li><b>把数据交给隧道</b>：对端发来的数据原样转给 {@link Listener#onPeerData}，由隧道去解析帧。
 * </ol>
 *
 * <p>线程：本类的回调来自 Paho 的 MQTT 线程（收消息）和 WebRTC 的原生线程（数据到达），
 * 因此内部共享状态都用并发容器或 {@code volatile}；回调里不做耗时操作，账号验证等慢活交给
 * {@code IdentityService} 的后台线程。
 */
public final class P2PSession implements AutoCloseable, SignallingClient.Listener {
    private static final Logger LOGGER = LoggerFactory.getLogger("mcp2p/session");

    /**
     * 会话事件回调。实现方（隧道）只应做轻量处理，不要阻塞。
     *
     * <p>这些回调可能在 WebRTC 或 MQTT 线程上被调用，**不是游戏主线程**。
     */
    public interface Listener {
        /** 某个对端的数据通道已经可用，可以开始传数据了。 */
        void onPeerReady(String peerId, String peerName);

        /** 某个对端离开或连接断开。 */
        void onPeerGone(String peerId, String reason);

        /** 收到某个对端的数据。{@code data} 是一份复制品，可以放心保存或跨线程使用。 */
        void onPeerData(String peerId, ByteBuffer data, boolean binary);

        /** 一句给人看的进度信息，通常写进日志或聊天栏。 */
        void onStatus(String status);
    }

    /** 配置（房间码、好友名单、端口等都从这里读）。 */
    private final McP2pConfig config;

    /** 本安装的 ID，同时用作 MQTT 客户端 ID 的一部分，用来区分对端。 */
    private final String installId;

    /** 其他对端看到的名字（有账号时用账号名，否则退回系统用户名）。 */
    private final String displayName;

    /** 上层（隧道）的回调。 */
    private final Listener listener;

    /** 账号服务：发挑战、应答挑战、核实对方的账号。 */
    private final IdentityService identity;

    /** WebRTC 引擎：按需创建原生工厂，进程内共享一份。 */
    private final WebRtcEngine engine = new WebRtcEngine();

    /** 对端 ID → 该对端的 WebRTC 会话。并发 Map，因为会在 MQTT 线程与 WebRTC 线程上读写。 */
    private final Map<String, PeerSession> peers = new ConcurrentHashMap<>();

    /** 对端 ID → 显示名，用于日志和聊天栏提示。 */
    private final Map<String, String> peerNames = new ConcurrentHashMap<>();

    /** 哪些对端在 hello 里声明了"我这边有世界可以进"。加入方会优先挑这样的对端。 */
    private final Set<String> hostingPeers = ConcurrentHashMap.newKeySet();

    /** 哪些对端已经通过账号证明。没有设置好友名单时这个集合不起作用（全部视为可信）。 */
    private final Set<String> verifiedPeers = ConcurrentHashMap.newKeySet();

    /** 对端 ID → 我们发给它的随机挑战串，用于核对它回执的是不是同一个挑战。 */
    private final Map<String, String> challenges = new ConcurrentHashMap<>();

    /** MQTT 客户端；{@link #leave()} 之后置为 null。 */
    private SignallingClient signalling;

    /** UDP 打洞协调器；在 {@link #join()} 时构造，命中后用于替代/并联 WebRTC 通道承载隧道流量。 */
    private PunchCoordinator punchCoordinator;

    /** 是否已经加入过房间（防止重复 join）。 */
    private volatile boolean joined;

    /** 本端是否声明"我在托管世界"；会随 hello 一起发出去。 */
    private volatile boolean hosting;

    public P2PSession(
            final McP2pConfig config,
            final String installId,
            final String displayName,
            final IdentityService identity,
            final Listener listener) {
        this.config = config;
        this.installId = installId;
        this.displayName = displayName;
        this.identity = identity;
        this.listener = listener;
    }

    /** Connects to the broker and announces this peer. Returns before the connection is up. */
    public void join() throws MqttException {
        if (joined) {
            return;
        }
        joined = true;
        signalling = new SignallingClient(config, installId, this);
        signalling.connect();
        punchCoordinator = createPunchCoordinator();
    }

    /** Says goodbye, closes every peer connection and disconnects. */
    public void leave() {
        if (!joined) {
            return;
        }
        joined = false;

        publish(SignalMessage.of(SignalMessage.Type.BYE, config.getRoomCode(), installId, displayName));
        for (String peerId : new ArrayList<>(peers.keySet())) {
            closePeer(peerId, "we left the room");
        }

        final SignallingClient client = signalling;
        signalling = null;
        if (client != null) {
            client.close();
        }
        if (punchCoordinator != null) {
            punchCoordinator.close();
            punchCoordinator = null;
        }
        engine.close();
    }

    @Override
    public void close() {
        leave();
    }

    /** @return the ids of the peers that have an open data channel */
    public List<String> connectedPeers() {
        final List<String> ready = new ArrayList<>();
        peers.forEach(
                (peerId, session) -> {
                    if (session.isOpen()) {
                        ready.add(peerId);
                    }
                });
        return ready;
    }

    /** Sends data to one peer. */
    public boolean send(final String peerId, final ByteBuffer data, final boolean binary) {
        // 优先走打洞通道（无加密开销、延迟最低）；WebRTC 作为兜底
        final UdpLink link = punchCoordinator == null
                ? null
                : punchCoordinator.readyLinks().get(peerId);
        if (link != null) {
            final byte[] bytes = new byte[data.remaining()];
            data.duplicate().get(bytes);
            link.send(bytes);
            return true;
        }
        final PeerSession session = peers.get(peerId);
        if (session == null) {
            return false;
        }
        session.send(data, binary);
        return true;
    }

    /** @return {@code true} when the peer's data channel can carry data */
    public boolean isPeerReady(final String peerId) {
        final PeerSession session = peers.get(peerId);
        return session != null && session.isOpen();
    }

    /** @return {@code true} when the peer announced that it shares a world */
    public boolean isHosting(final String peerId) {
        return hostingPeers.contains(peerId);
    }

    /** @return the name of a peer that shares a world, or {@code null} when there is none */
    public String hostingPeerName() {
        for (String peerId : connectedPeers()) {
            if (hostingPeers.contains(peerId)) {
                return peerNames.getOrDefault(peerId, peerId);
            }
        }
        return null;
    }

    /** @return the room this session is in */
    public String roomCode() {
        return config.getRoomCode();
    }

    /** @return {@code true} when the room requires peers to prove a shared secret */
    public boolean isSecured() {
        return !config.getRoomSecret().isBlank();
    }

    /** Checks the room secret proof; a room without a secret accepts every peer. */
    private boolean accepts(final SignalMessage message) {
        if (!isSecured()) {
            return true;
        }
        return RoomSecret.matches(
                RoomSecret.proof(config.getRoomSecret(), message.room, message.from, message.type),
                message.proof);
    }

    /** @return the bytes queued in the peer's data channel, {@code 0} when it is not connected */
    public long bufferedAmount(final String peerId) {
        final PeerSession session = peers.get(peerId);
        return session == null ? 0L : session.bufferedAmount();
    }

    /** Tells the room whether this peer shares a world; it is announced with the next hello. */
    public void setHosting(final boolean hosting) {
        this.hosting = hosting;
    }

    private void publish(final SignalMessage message) {
        final SignallingClient client = signalling;
        if (client == null) {
            return;
        }
        // Every outgoing message proves that we know the room secret, when there is one. The secret is
        // read per message, so that starting to host can switch protection on for an open session.
        message.proof =
                RoomSecret.proof(
                        config.getRoomSecret(), config.getRoomCode(), installId, message.type);
        client.publish(message);
    }

    /**
     * 构建 WebRTC 连接参数——这里是"打洞成功率"最集中的调节点。
     *
     * <p>各条措施对应的原理：
     *
     * <ul>
     *   <li><b>每个 STUN 一个 ICE 服务器</b>：能拿到多少个反射地址，就有多少条路径可供对方的连接检查
     *       尝试。当只有一侧处于对称 NAT 后面时（最常见的情况），靠的正是这一侧收集到的候选。
     *   <li><b>TURN 带独立凭证</b>：配置后 ICE 会自动增加中继候选；双方都在对称 NAT 后面时，中继是唯一
     *       可靠的通路。
     *   <li><b>IPv6 默认开启</b>：只要双方都有公网 IPv6，流量直接走 IPv6，完全不经过 NAT。
     *   <li><b>可选的本地端口范围</b>：把候选限制在一段固定端口区间，某些 NAT 会更稳定地复用同一映射。
     *   <li><b>{@code relay} 策略</b>：强制只用 TURN，避免把双方 IP 暴露给对方。
     * </ul>
     */
    private RTCConfiguration rtcConfiguration() {
        final RTCConfiguration configuration = new RTCConfiguration();

        // 1) STUN：每个 URI 一个 ICE 服务器
        for (String url : config.iceServerUrls()) {
            final RTCIceServer server = new RTCIceServer();
            server.urls.add(url);
            configuration.iceServers.add(server);
        }

        // 2) TURN：每个 URI 一个 ICE 服务器，并带上凭证
        for (String url : config.turnServerUrls()) {
            final RTCIceServer server = new RTCIceServer();
            server.urls.add(url);
            if (!config.getTurnUsername().isBlank()) {
                server.username = config.getTurnUsername();
                server.password = config.getTurnPassword();
            }
            configuration.iceServers.add(server);
        }

        // 3) 只走中继时，把主机候选与反射候选整体排除
        if (config.forcesRelay()) {
            configuration.iceTransportPolicy = RTCIceTransportPolicy.RELAY;
        }

        // 4) 候选端口范围（0 表示不限）与 IPv6 开关
        final PortAllocatorConfig ports = configuration.portAllocatorConfig;
        if (config.getIcePortMin() > 0 && config.getIcePortMax() >= config.getIcePortMin()) {
            ports.minPort = config.getIcePortMin();
            ports.maxPort = config.getIcePortMax();
        }
        ports.setEnableIpv6(config.isIpv6Enabled());

        return configuration;
    }

    @Override
    public void onConnected(final boolean reconnect) {
        listener.onStatus("Signalling broker connected");
        if (reconnect) {
            // The broker restarted or the link dropped: everything negotiated before is stale.
            for (String peerId : new ArrayList<>(peers.keySet())) {
                closePeer(peerId, "the signalling connection was re-established");
            }
        }
        announce(null);
    }

    @Override
    public void onDisconnected(final Throwable cause) {
        listener.onStatus(
                "Signalling broker unreachable: "
                        + (cause == null ? "unknown reason" : cause.getMessage()));
    }

    /**
     * 收到一条信令消息（可能来自房间里的任何人）。
     *
     * <p>这里是一道"过滤器 + 分派器"：先排除掉不该处理的（自己发的、版本不符、不是给我的、没有房间密钥
     * 证明的、房间不对的），再按类型交给对应的 handle 方法。
     *
     * <p>线程：这个方法运行在 Paho 的 MQTT 线程上，所以里面的处理都要么很快，要么丢给后台线程。
     */
    @Override
    public void onMessage(final SignalMessage message) {
        // MQTT 会把消息回送给发布者自己，所以先丢掉"我发的"
        if (message.from.equals(installId)) {
            return;
        }
        // 版本不同说明对方是另一个（未来或更旧的）协议版本，听不懂就别乱猜
        if (message.version != SignalMessage.VERSION) {
            LOGGER.warn(
                    "Ignoring {}: it speaks signalling protocol version {}",
                    message.from,
                    message.version);
            return;
        }
        // 消息里可能带 to；只处理"发给我的"或"广播"的
        if (!message.isFor(installId)) {
            return;
        }
        // 房间密钥证明：没有它就不该继续，连 WebRTC 对象都不创建
        if (!accepts(message)) {
            LOGGER.warn(
                    "Ignoring a {} from {}: it does not prove that it knows the room secret",
                    message.type,
                    message.from);
            return;
        }
        // 房间码也要对得上（同一主题下可能混进别的房间的重放消息）
        if (message.room != null && !message.room.equals(config.getRoomCode())) {
            LOGGER.debug("Ignoring a message that belongs to room {}", message.room);
            return;
        }

        peerNames.put(message.from, message.fromName == null ? message.from : message.fromName);
        if (message.hosting != null) {
            if (message.hosting) {
                hostingPeers.add(message.from);
            } else {
                hostingPeers.remove(message.from);
            }
        }

        switch (message.type) {
            case HELLO:
                handleHello(message);
                break;
            case BYE:
                closePeer(
                        message.from,
                        peerNames.getOrDefault(message.from, message.from) + " left the room");
                break;
            case OFFER:
                handleOffer(message);
                break;
            case ANSWER:
                handleAnswer(message);
                break;
            case CANDIDATE:
                handleCandidate(message);
                break;
            case CHALLENGE:
                handleChallenge(message);
                break;
            case CHALLENGE_RESPONSE:
                handleChallengeResponse(message);
                break;
            case VERIFIED:
                handleVerified(message);
                break;
            case STUN_SAMPLES:
                if (punchCoordinator != null) {
                    punchCoordinator.learnRemoteSamples(message.from, message.stunSamples, message.punchAddress);
                }
                break;
            case PUNCH_PORT:
                if (punchCoordinator != null && message.punchPort != null) {
                    punchCoordinator.onPunchPort(message.from, message.punchPort);
                }
                break;
        }
    }

    private void announce(final String to) {
        final SignalMessage hello =
                SignalMessage.of(
                        SignalMessage.Type.HELLO, config.getRoomCode(), installId, displayName);
        hello.to = to;
        hello.hosting = hosting;
        hello.requiresVerification = verificationRequired();
        hello.uuid = identity.identity().map(PlayerIdentity::uuid).orElse(null);
        publish(hello);
    }

    private void handleHello(final SignalMessage message) {
        if (peers.containsKey(message.from) || challenges.containsKey(message.from)) {
            return;
        }
        LOGGER.info("Peer {} ({}) is in the room", message.from, message.fromName);
        listener.onStatus(message.fromName + " joined the room");

        if (verificationRequired()) {
            // Nothing is negotiated with this peer before it proved its account.
            requestVerification(message.from);
            if (message.isBroadcast()) {
                announce(message.from);
            }
            return;
        }

        onPeerReadyToNegotiate(message.from);

        if (message.isBroadcast()) {
            // Answer broadcasts, so a peer that joined while we were online learns about us too.
            announce(message.from);
        }
    }

    /** Picks the initiating side: the lower installation id wins, so no two offers are sent. */
    private void onPeerReadyToNegotiate(final String peerId) {
        // 同时启动 UDP 打洞——与 WebRTC 协商并行；只要任一通道建好，隧道就可以走它
        if (punchCoordinator != null) {
            attachPunchListener(peerId);
            punchCoordinator.startWith(peerId);
        }
        if (installId.compareTo(peerId) < 0) {
            initiate(peerId);
        }
    }

    /** 构造 {@link PunchCoordinator}，把 STUN 服务器列表、房间密钥 token、信令出口等准备好。 */
    private PunchCoordinator createPunchCoordinator() {
        final String[] entries = config.getStunServers().split("[,;\\s]+");
        final List<InetSocketAddress> stunServers = new ArrayList<>();
        for (String entry : entries) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            try {
                stunServers.add(parseStunAddress(entry.trim()));
            } catch (Exception ignored) {
                // 忽略格式错误的条目
            }
        }
        // 把房间密钥派生为 8 字节打洞令牌（与对端独立计算得到同一份 token）
        final byte[] token = derivePunchToken(config.getRoomSecret());
        final PunchCoordinator coordinator =
                new PunchCoordinator(token, stunServers, installId, displayName, config.getRoomCode(), signalling);
        // PunchCoordinator 内部为每个 peer 维护自己的回调：这里只装一次，监听所有 peer 的上行字节
        // 并把它们喂给上层 listener（等价于 WebRTC 数据通道的回调）。新 peer 在 onPeerReadyToNegotiate
        // 里启动打洞时会自动绑定同一条 listener（构造函数内的回调对 coordinator 来说是 peerId 无关的）。
        final PunchCoordinator.LinkListener universalListener =
                (peerId, payload) ->
                        listener.onPeerData(peerId, ByteBuffer.wrap(payload), true);
        for (String peerId : new ArrayList<>(peers.keySet())) {
            coordinator.setLinkListener(peerId, universalListener);
        }
        return coordinator;
    }

    /**
     * 给一个对端装上"打洞通道收包"回调。P2PSession 在每条 peer 进入打洞流程时调用，确保收到的字节
     * 能被同一个 listener.onPeerData 接收（与 WebRTC 数据通道共用）。
     */
    public void attachPunchListener(final String peerId) {
        if (punchCoordinator == null) {
            return;
        }
        punchCoordinator.setLinkListener(
                peerId,
                (id, payload) -> listener.onPeerData(id, ByteBuffer.wrap(payload), true));
    }

    private static InetSocketAddress parseStunAddress(final String entry) {
        final String hostPort;
        if (entry.contains("://")) {
            hostPort = entry.substring(entry.indexOf("://") + 3);
        } else {
            hostPort = entry;
        }
        final int colon = hostPort.lastIndexOf(':');
        if (colon < 0) {
            return new InetSocketAddress(hostPort, 3478);
        }
        final String host = hostPort.substring(0, colon);
        final int port = Integer.parseInt(hostPort.substring(colon + 1));
        return new InetSocketAddress(host, port);
    }

    /**
     * 把房间密钥派生为 8 字节打洞令牌：空房间用固定常量（无加密保护，但房间本身开放也不需要证明身份）；
     * 有密钥时用 SHA-256 的前 8 字节。两端用同一个房间密钥自然派生同一份 token。
     */
    private static byte[] derivePunchToken(final String secret) {
        if (secret == null || secret.isBlank()) {
            // 开放房间的固定令牌：双方都能算出，但没有任何"持有房间密钥"的语义
            return "mcp2p-open".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        }
        try {
            final byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            final byte[] token = new byte[8];
            System.arraycopy(digest, 0, token, 0, 8);
            return token;
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    /** @return {@code true} when only accounts from the friend list may connect */
    private boolean verificationRequired() {
        return !config.friendList().isEmpty();
    }

    /** @return {@code true} when nothing is required, or the peer proved itself already */
    private boolean isTrusted(final String peerId) {
        return !verificationRequired() || verifiedPeers.contains(peerId);
    }

    private void requestVerification(final String peerId) {
        final String serverId = UUID.randomUUID().toString().replace("-", "");
        challenges.put(peerId, serverId);

        final SignalMessage challenge =
                SignalMessage.of(
                        SignalMessage.Type.CHALLENGE,
                        config.getRoomCode(),
                        installId,
                        displayName);
        challenge.to = peerId;
        challenge.challenge = serverId;
        publish(challenge);
        LOGGER.info("Asked {} to prove its account", peerId);
    }

    /** Joining side: answer the host's challenge through the account service. */
    private void handleChallenge(final SignalMessage message) {
        if (message.challenge == null) {
            return;
        }
        LOGGER.info("{} asked us to prove our account", message.from);

        identity.answerChallengeAsync(
                message.challenge,
                error -> {
                    if (error != null) {
                        listener.onStatus(error);
                        return;
                    }

                    final SignalMessage response =
                            SignalMessage.of(
                                    SignalMessage.Type.CHALLENGE_RESPONSE,
                                    config.getRoomCode(),
                                    installId,
                                    displayName);
                    response.to = message.from;
                    response.challenge = message.challenge;
                    response.uuid = identity.identity().map(PlayerIdentity::uuid).orElse(null);
                    publish(response);
                });
    }

    /** Hosting side: check the proof, then let the peer in when its account is on the list. */
    private void handleChallengeResponse(final SignalMessage message) {
        final String expected = challenges.get(message.from);
        if (expected == null || !expected.equals(message.challenge)) {
            LOGGER.warn("Ignoring a challenge response from {} that does not match", message.from);
            return;
        }

        identity.verifyAsync(
                message.fromName,
                expected,
                verified -> {
                    if (verified.isEmpty()) {
                        LOGGER.warn(
                                "{} claimed the account {} but did not prove it",
                                message.from,
                                message.fromName);
                        listener.onStatus(message.fromName + " could not prove its account");
                        challenges.remove(message.from);
                        return;
                    }

                    final PlayerIdentity account = verified.get();
                    if (!isAccountAllowed(account)) {
                        LOGGER.warn(
                                "Account {} is not on the friend list, ignoring {}",
                                account.name(),
                                message.from);
                        listener.onStatus(account.name() + " is not on the friend list");
                        challenges.remove(message.from);
                        return;
                    }

                    peerNames.put(message.from, account.name());
                    verifiedPeers.add(message.from);
                    challenges.remove(message.from);
                    LOGGER.info("{} proved the account {}", message.from, account.name());

                    final SignalMessage confirmed =
                            SignalMessage.of(
                                    SignalMessage.Type.VERIFIED,
                                    config.getRoomCode(),
                                    installId,
                                    displayName);
                    confirmed.to = message.from;
                    publish(confirmed);

                    onPeerReadyToNegotiate(message.from);
                });
    }

    /** Joining side: the host accepted the proof, so negotiation may start. */
    private void handleVerified(final SignalMessage message) {
        LOGGER.info("{} accepted our account proof", message.from);
        verifiedPeers.add(message.from);
        onPeerReadyToNegotiate(message.from);
    }

    private boolean isAccountAllowed(final PlayerIdentity account) {
        for (String entry : config.friendList()) {
            if (account.matches(entry)) {
                return true;
            }
        }
        return false;
    }

    private void handleOffer(final SignalMessage message) {
        if (!isTrusted(message.from)) {
            LOGGER.debug("Ignoring an offer from {}: its account is not proved", message.from);
            return;
        }
        try {
            peerSession(message.from).handleOffer(toDescription(message, RTCSdpType.OFFER));
        } catch (RuntimeException e) {
            LOGGER.error("Could not answer the offer of {}", message.from, e);
        }
    }

    private void handleAnswer(final SignalMessage message) {
        final PeerSession session = peers.get(message.from);
        if (session == null || !isTrusted(message.from)) {
            LOGGER.debug("Ignoring an answer from {}: no trusted session", message.from);
            return;
        }
        session.handleAnswer(toDescription(message, RTCSdpType.ANSWER));
    }

    private void handleCandidate(final SignalMessage message) {
        final PeerSession session = peers.get(message.from);
        if (session == null || !isTrusted(message.from)) {
            LOGGER.debug("Ignoring a candidate from {}: no trusted session", message.from);
            return;
        }
        final int mLineIndex = message.sdpMLineIndex == null ? 0 : message.sdpMLineIndex;
        session.addRemoteCandidate(new RTCIceCandidate(message.sdpMid, mLineIndex, message.sdp));
    }

    private void initiate(final String peerId) {
        if (peers.containsKey(peerId) || !isTrusted(peerId)) {
            return;
        }
        try {
            peerSession(peerId).startAsInitiator();
            LOGGER.info("Started negotiating with {}", peerId);
        } catch (RuntimeException e) {
            LOGGER.error("Could not start a session with {}", peerId, e);
        }
    }

    /** Returns the session of a peer, creating it in the answering role when needed. */
    private PeerSession peerSession(final String peerId) {
        return peers.computeIfAbsent(
                peerId,
                id ->
                        new PeerSession(
                                engine.factory(),
                                rtcConfiguration(),
                                id,
                                config.getDataChannelLabel(),
                                config.getIceRestartAttempts(),
                                peerListener(id)));
    }

    private void closePeer(final String peerId, final String reason) {
        hostingPeers.remove(peerId);
        verifiedPeers.remove(peerId);
        challenges.remove(peerId);
        if (punchCoordinator != null) {
            punchCoordinator.drop(peerId);
        }
        final PeerSession session = peers.remove(peerId);
        if (session == null) {
            return;
        }
        session.close();
        LOGGER.info("Closed the session with {}: {}", peerId, reason);
        listener.onPeerGone(peerId, reason);
    }

    private static RTCSessionDescription toDescription(
            final SignalMessage message, final RTCSdpType fallback) {
        RTCSdpType type = fallback;
        if (message.sdpType != null) {
            for (RTCSdpType candidate : RTCSdpType.values()) {
                if (candidate.name().equals(message.sdpType)) {
                    type = candidate;
                    break;
                }
            }
        }
        return new RTCSessionDescription(type, message.sdp);
    }

    /** Routes the events of one peer connection back into signalling. */
    private PeerSession.Listener peerListener(final String peerId) {
        return new PeerSession.Listener() {
            @Override
            public void onLocalDescription(final RTCSessionDescription description) {
                final SignalMessage message =
                        SignalMessage.of(
                                description.sdpType == RTCSdpType.OFFER
                                        ? SignalMessage.Type.OFFER
                                        : SignalMessage.Type.ANSWER,
                                config.getRoomCode(),
                                installId,
                                displayName);
                message.to = peerId;
                message.sdp = description.sdp;
                message.sdpType = description.sdpType.name();
                publish(message);
                LOGGER.debug("Signalled a {} to {}", description.sdpType, peerId);
            }

            @Override
            public void onLocalCandidate(final RTCIceCandidate candidate) {
                final SignalMessage message =
                        SignalMessage.of(
                                SignalMessage.Type.CANDIDATE,
                                config.getRoomCode(),
                                installId,
                                displayName);
                message.to = peerId;
                message.sdpMid = candidate.sdpMid;
                message.sdpMLineIndex = candidate.sdpMLineIndex;
                message.sdp = candidate.sdp;
                publish(message);
            }

            @Override
            public void onDataChannelOpen() {
                listener.onPeerReady(peerId, peerNames.getOrDefault(peerId, peerId));
            }

            @Override
            public void onMessage(final ByteBuffer data, final boolean binary) {
                listener.onPeerData(peerId, data, binary);
            }

            @Override
            public void onConnectionStateChanged(final RTCPeerConnectionState state) {
                listener.onStatus("Peer " + peerId + " is " + state);
            }

            @Override
            public void onClosed(final String reason) {
                closePeer(peerId, reason);
            }
        };
    }
}
