package com.example.elink.punch;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 生日悖论式（Birthday Paradox）UDP 打洞：用来对付**双侧都是对称 NAT**的直连难题。
 *
 * <p>问题：单个端口能命中的概率极低——我们不知道对方在与我方通信时会被 NAT 分配哪个端口。
 *
 * <p>思路（生日悖论）：让双方各自同时使用 <b>N 个本地端口</b>，并向对方**一整段预测端口**（由 STUN 观测
 * 结果推断出的区间，宽 W）不断发送探测包。任意一对 "我方端口 ↔ 对方端口" 恰好互相可达即算命中，组合数约
 * N×W，命中概率随之快速上升（当 N 与 W 都在几百量级时，命中概率已经很高）。
 *
 * <p>命中判定必须**双向**：只有当我们发出的探测包被对方收到（对方回 ACK），或我们收到对方的探测包时，
 * 才说明两个 NAT 都放行了这条路径。因此两端都要运行本类，并由信令交换各自的 STUN 观测样本。
 *
 * <p>线程：{@link #punch} 是阻塞式的（内部循环），请放在后台线程执行。
 */
public final class HolePuncher implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("elink/punch");

    /** 探测包格式：魔数(4) + 版本(1) + 类型(1) + 令牌(8)。没有多余字段，越小越不容易被分片。 */
    private static final byte[] MAGIC = {'M', 'C', 'P', '2'};
    private static final byte VERSION = 1;
    private static final byte TYPE_PROBE = 1;
    private static final byte TYPE_ACK = 2;
    private static final int TOKEN_SIZE = 8;
    private static final int PACKET_SIZE = 4 + 1 + 1 + TOKEN_SIZE;

    /** 打洞进度与结果回调。 */
    public interface Listener {
        /** 一句给人看的进度信息。 */
        void onProgress(String status);

        /** 找到了一条双向可达的路径。 */
        void onHit(Path path);

        /** 时间用尽或出错，放弃这次尝试。 */
        void onGiveUp(String reason);
    }

    /** 某个本地端口的 STUN 观测结果（NAT 把它映射成了哪个公网端口）。 */
    public record MappingSample(int localPort, int mappedPort) {}

    /**
     * 命中的路径：一个已经与对方连通的 UDP 通道，以及对方的公网地址。
     *
     * <p>这个路径只描述"我从这边打到对方"的通道和远端地址。生日悖论扫描下两端命中的可能是**不同**的
     * 本地端口（例如 A 用 53163、B 用 53168），这时 A 发到 B 的 53166 而 B 用 53168 去收，根本收不到。
     * 因此收到命中后还要再发一次"我自己用来通信的本地端口"给对方，由对方把目标改成那个端口。
     */
    public record Path(DatagramChannel channel, InetSocketAddress remote) {}

    private final List<DatagramChannel> channels = new ArrayList<>();

    /**
     * 打开若干个本地 UDP 通道。绑定端口传 0，由系统分配；对端看到的将是 NAT 分配的公网端口。
     *
     * @param sockets 本地通道数量（N），越大命中概率越高，代价是更多包与更多 NAT 会话
     */
    public HolePuncher(final int sockets) throws IOException {
        final int count = Math.max(1, sockets);
        for (int i = 0; i < count; i++) {
            final DatagramChannel channel = DatagramChannel.open();
            channel.setOption(StandardSocketOptions.SO_REUSEADDR, true);
            channel.configureBlocking(false);
            channel.bind(new InetSocketAddress("0.0.0.0", 0));
            channels.add(channel);
        }
        LOGGER.debug("Opened {} UDP sockets for hole punching", channels.size());
    }

    /** @return 本机当前打开的所有通道数量 */
    public int socketCount() {
        return channels.size();
    }

    /**
     * @return 本机各通道的本地端口（顺序与内部一致）。
     *
     * <p>打洞本身并不需要它，但它在调试与自测时很有用：没有 NAT 的环境里，直接拿这些端口当"目标端口"
     * 就能验证整套扫描/命中/建链逻辑。
     */
    public List<Integer> localPorts() {
        final List<Integer> ports = new ArrayList<>();
        for (DatagramChannel channel : channels) {
            try {
                ports.add(((InetSocketAddress) channel.getLocalAddress()).getPort());
            } catch (IOException e) {
                LOGGER.debug("Reading a local punch port: {}", e.getMessage());
            }
        }
        return ports;
    }

    /** 暴露给自测/管理代码用：拿走所有通道的引用（不负责关闭它们）。 */
    public List<DatagramChannel> allChannels() {
        return new ArrayList<>(channels);
    }

    /** @return 关闭 puncher 中除了指定保留通道以外的全部通道；保留通道交给上层继续使用。 */
    public void closeOthers(final DatagramChannel keep) {
        for (DatagramChannel channel : channels) {
            if (channel != keep && channel.isOpen()) {
                try {
                    channel.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    /**
     * 向 STUN 服务器查询每个通道被映射成的公网端口，得到"样本"。
     *
     * <p>这些样本要交给对方（通过 MQTT 信令），对方据此预测我方可能使用的端口区间；反之亦然。
     * 对称 NAT 下这些端口**不是**我们与对方通信时真正使用的端口，但它们能反映 NAT 的分配规律。
     *
     * @param stunServers   至少要有一个可用的 STUN 服务器
     * @param timeoutMillis 每个通道的查询超时
     */
    public List<MappingSample> observe(
            final List<InetSocketAddress> stunServers, final int timeoutMillis) {
        final List<MappingSample> samples = new ArrayList<>();

        for (DatagramChannel channel : channels) {
            for (InetSocketAddress server : stunServers) {
                final Optional<StunClient.Mapping> mapping =
                        StunClient.query(channel, server, timeoutMillis);
                if (mapping.isPresent()) {
                    int localPort = -1;
                    try {
                        localPort = ((InetSocketAddress) channel.getLocalAddress()).getPort();
                    } catch (IOException e) {
                        LOGGER.debug("Reading a local punch port: {}", e.getMessage());
                    }
                    samples.add(new MappingSample(localPort, mapping.get().port()));
                    break; // 这个通道有一个观测结果就够了
                }
            }
        }
        return samples;
    }

    /**
     * 由对方的映射样本推断出要扫描的目标端口。
     *
     * <p>大多数 NAT 在连续分配端口（+1 递增），因此在样本附近的一段区间内扫描即可覆盖对方真正使用的端口。
     *
     * @param mappedPort 对方上报的某个映射端口
     * @param width      样本左右各扫描多少个端口
     */
    public static int[] predictTargets(final int mappedPort, final int width) {
        final int from = Math.max(1, mappedPort - Math.max(0, width));
        final int to = Math.min(65535, mappedPort + Math.max(0, width));
        final int[] targets = new int[to - from + 1];
        for (int i = 0; i < targets.length; i++) {
            targets[i] = from + i;
        }
        return targets;
    }

    /** 推断 NAT 的端口分配步长（相邻样本差的最小正值），仅用于日志与诊断。 */
    public static int inferStep(final List<MappingSample> samples) {
        if (samples.size() < 2) {
            return 0;
        }
        int step = 0;
        for (int i = 1; i < samples.size(); i++) {
            final int delta = samples.get(i).mappedPort() - samples.get(i - 1).mappedPort();
            if (delta > 0 && (step == 0 || delta < step)) {
                step = delta;
            }
        }
        return step;
    }

    @Override
    public void close() {
        for (DatagramChannel channel : channels) {
            try {
                if (channel.isOpen()) {
                    channel.close();
                }
            } catch (IOException e) {
                LOGGER.debug("Closing a punch socket: {}", e.getMessage());
            }
        }
        channels.clear();
    }

    /**
     * 开始扫描，直到命中或时间用尽。
     *
     * <p>两端都要调用本方法，并各自把对方的预测端口当作 {@code targets}。一侧先收到探测包时会立刻回
     * ACK，另一侧收到 ACK 也算命中，所以只要存在一对互通端口，双方都会得到结果。
     *
     * @param remoteAddress    对方的公网地址（从 MQTT 消息里带过来）
     * @param targets          要扫描的对方端口（通常由 {@link #predictTargets} 生成）
     * @param token            8 字节共享令牌，由房间密钥派生，用来确认对方身份
     * @param durationMillis   最长尝试时间
     * @param packetsPerSecond 每秒发送的探测包总数（会均摊到所有通道）
     * @param listener         进度与结果回调
     * @return 命中的路径；没命中返回 empty
     */
    public Optional<Path> punch(
            final InetAddress remoteAddress,
            final int[] targets,
            final byte[] token,
            final long durationMillis,
            final int packetsPerSecond,
            final Listener listener) {
        if (targets.length == 0 || token.length != TOKEN_SIZE) {
            listener.onGiveUp("打洞参数不完整");
            return Optional.empty();
        }

        final Selector selector;
        try {
            selector = Selector.open();
            for (DatagramChannel channel : channels) {
                channel.register(selector, SelectionKey.OP_READ);
            }
        } catch (IOException e) {
            listener.onGiveUp("无法准备打洞通道：" + e.getMessage());
            return Optional.empty();
        }

        listener.onProgress(
                "开始扫描：" + channels.size() + " 个本地端口 × " + targets.length + " 个目标端口");

        final ByteBuffer receive = ByteBuffer.allocate(256);
        final ByteBuffer probe = buildPacket(TYPE_PROBE, token);
        final ByteBuffer ack = buildPacket(TYPE_ACK, token);
        // 把每秒包数均摊到每个通道，避免某个通道发得太快触发 NAT 限速或丢包
        final long intervalNanos =
                Math.max(
                        200_000L,
                        1_000_000_000L * channels.size() / Math.max(1, packetsPerSecond));
        final long deadline = System.currentTimeMillis() + durationMillis;
        int targetIndex = 0;
        long nextSend = System.nanoTime();

        try {
            while (System.currentTimeMillis() < deadline) {
                selector.selectNow(); // 非阻塞地看一眼有没有回包

                final Iterator<SelectionKey> keys = selector.selectedKeys().iterator();
                while (keys.hasNext()) {
                    final SelectionKey key = keys.next();
                    keys.remove();
                    final DatagramChannel channel = (DatagramChannel) key.channel();
                    final Optional<Path> hit = readPackets(channel, receive, token, ack);
                    if (hit.isPresent()) {
                        selector.close();
                        listener.onHit(hit.get());
                        return hit;
                    }
                }

                final long now = System.nanoTime();
                if (now >= nextSend) {
                    nextSend = now + intervalNanos;
                    for (DatagramChannel channel : channels) {
                        final int target = targets[targetIndex++ % targets.length];
                        channel.send(
                                probe.duplicate(), new InetSocketAddress(remoteAddress, target));
                    }
                }
            }
        } catch (IOException e) {
            listener.onGiveUp("打洞过程中出错：" + e.getMessage());
            return Optional.empty();
        }

        listener.onGiveUp("扫描结束，没有找到可用的路径");
        return Optional.empty();
    }

    /** 读空某个通道收到的探测/确认包；发现对方时就返回这条路径。 */
    private Optional<Path> readPackets(
            final DatagramChannel channel,
            final ByteBuffer receive,
            final byte[] token,
            final ByteBuffer ack)
            throws IOException {
        while (true) {
            receive.clear();
            final InetSocketAddress from = (InetSocketAddress) channel.receive(receive);
            if (from == null) {
                return Optional.empty(); // 这个通道上暂时没有更多数据
            }
            if (receive.position() < PACKET_SIZE) {
                continue; // 太短，肯定不是我们的协议包
            }

            receive.flip();
            final byte[] packet = new byte[PACKET_SIZE];
            receive.get(packet, 0, PACKET_SIZE);
            if (!matchesToken(packet, token)) {
                continue; // 不是本次打洞的包（旧会话或端口扫描器），忽略
            }

            final byte type = packet[5];
            if (type == TYPE_PROBE) {
                // 对方已经能打进来：马上回一个 ACK，让对方也能确认这条路径可用
                channel.send(ack.duplicate(), from);
                LOGGER.info("Hole punch hit: local port {} <- {}", channel.getLocalAddress(), from);
                return Optional.of(new Path(channel, from));
            }
            if (type == TYPE_ACK) {
                LOGGER.info(
                        "Hole punch confirmed: local port {} -> {}",
                        channel.getLocalAddress(),
                        from);
                return Optional.of(new Path(channel, from));
            }
        }
    }

    /**
     * 由一端运行（通常是先命中那一端），另一端负责调用 {@link #selectForRemote} 把目标地址改成它。
     *
     * <p>问题是生日悖论扫描时两端可能命中的是不同的本地端口：A 用本地端口 X 命中对方端口 P，但 B 用
     * 本地端口 Y 命中 A 的端口 Q；这时 A→B 的包会送到 P，B 在 Y 端口上根本看不到。
     *
     * <p>解法：命中后两端通过 MQTT 信令交换"我自己通信用的本地端口"，然后把 UdpLink 的目标地址改成对
     * 方发来的端口。这里只是协商工具，{@link UdpLink} 自己使用协商后的端口。
     *
     * <p>这个方法只是简单地"选好用来通信的 socket 并返回它的本地端口"：返回的是这个 puncher 中**第一个
     * 仍打开的通道**的本地端口，调用方拿这个值去通知对方。
     */
    public int localPortForCommunication() {
        for (DatagramChannel channel : channels) {
            if (channel.isOpen()) {
                try {
                    return ((InetSocketAddress) channel.getLocalAddress()).getPort();
                } catch (IOException ignored) {
                    // 继续看下一个
                }
            }
        }
        return -1;
    }

    private static ByteBuffer buildPacket(final byte type, final byte[] token) {
        final ByteBuffer buffer = ByteBuffer.allocate(PACKET_SIZE);
        buffer.put(MAGIC).put(VERSION).put(type).put(token);
        buffer.flip();
        return buffer;
    }

    private static boolean matchesToken(final byte[] packet, final byte[] token) {
        for (int i = 0; i < MAGIC.length; i++) {
            if (packet[i] != MAGIC[i]) {
                return false;
            }
        }
        if (packet[4] != VERSION) {
            return false;
        }
        for (int i = 0; i < TOKEN_SIZE; i++) {
            if (packet[6 + i] != token[i]) {
                return false;
            }
        }
        return true;
    }
}
