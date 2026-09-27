package com.example.elink.punch;

import com.example.elink.signalling.SignalMessage;
import com.example.elink.signalling.SignallingClient;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.channels.DatagramChannel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 编排一整轮"生日悖论 + STUN"打洞：每条对端在 {@link #startWith} 时各跑一次完整流程。
 *
 * <p>流程：
 * <ol>
 *   <li>开 {@link HolePuncher}（N 个本地 socket）。</li>
 *   <li>向对方发 {@code STUN_SAMPLES}：把本机对若干 STUN 服务器的反射端口告诉对方。</li>
 *   <li>在 {@link #learnRemoteSamples} 收到对方的 STUN 样本后，按它们的中位数 ± 一个宽度生成
 *       预测端口区间；同时启动本机的扫描，目标是"对方的全部预测端口"。</li>
 *   <li>任何一端先收到对端的探测包，立刻回 ACK；两端都得到一条命中路径。</li>
 *   <li>命中后互相发 {@code PUNCH_PORT}：双方都把发送目标改成对方"用于通信的本地端口"，避免
 *       生日悖论扫描两端命中不同本地端口时，A 发到 B 的旧端口而 B 在新端口上收不到的问题。</li>
 *   <li>最后 {@link #onPunchPort} 收到对方端口，构造出 {@link UdpLink}，交给上层承载 Minecraft 流量。</li>
 * </ol>
 *
 * <p>本类**不是线程**——所有方法都在 MQTT 回调或 P2PSession 的事件线程上调用；每个 peer 自己
 * 维护的状态用 {@link AtomicReference} 串行更新。
 */
public final class PunchCoordinator {
    private static final Logger LOGGER = LoggerFactory.getLogger("elink/punch");

    /** 预测目标端口时，对方单个 STUN 样本左右各扩展多少。 */
    private static final int TARGET_WIDTH_PER_SAMPLE = 32;

    /** 单次扫描尝试的最长持续时间。 */
    private static final long PUNCH_DURATION_MS = 6_000;

    /** 每秒发送多少探测包；稍高一点能在对称 NAT 下更快命中。 */
    private static final int PUNCH_RATE_PER_SECOND = 800;

    /** 共享一个房间密钥派生出来的 8 字节令牌，作为打洞与 UdpLink 密钥的来源。 */
    private final byte[] roomToken;

    /** 房间的 STUN 服务器列表（来自 {@link com.example.elink.config.ELinkConfig#getStunServers()}）。 */
    private final List<InetSocketAddress> stunServers;

    /** MQTT 信令出口。 */
    private final SignallingClient signalling;

    /** 本安装 ID，用于消息里的 {@code from}。 */
    private final String installId;

    /** 显示名，仅用于日志。 */
    private final String displayName;

    /** 房间码，写进消息的 {@code room}。 */
    private final String roomCode;

    /** 每个对端当前一轮打洞的状态。 */
    private final Map<String, PeerPunchState> states = new ConcurrentHashMap<>();

    /** 每个对端最终命中的链路；命中的瞬间就启动它，没命中则一直为 empty。 */
    private final Map<String, Optional<UdpLink>> links = new ConcurrentHashMap<>();

    public PunchCoordinator(
            final byte[] roomToken,
            final List<InetSocketAddress> stunServers,
            final String installId,
            final String displayName,
            final String roomCode,
            final SignallingClient signalling) {
        this.roomToken = roomToken;
        this.stunServers = stunServers;
        this.installId = installId;
        this.displayName = displayName;
        this.roomCode = roomCode;
        this.signalling = signalling;
    }

    /**
     * 给一个对端装上"打洞通道收包"回调——通常是 {@code P2PSession} 内部把收到的字节直接转给隧道。
     */
    public void setLinkListener(final String peerId, final LinkListener listener) {
        final PeerPunchState state = states.computeIfAbsent(peerId, id -> new PeerPunchState(id));
        state.coordinatorListener = listener;
    }

    /**
     * 从 P2PSession 角度看到的"打洞通道回调"：上行字节由隧道消费；下行字节由 P2PSession.send 走。
     */
    public interface LinkListener {
        void onUdpPayload(String peerId, byte[] payload);
    }

    /** 对端的 STUN 样本到达（也可能是我们自己发出后的回声，这里简单忽略自己）。 */
    public void learnRemoteSamples(
            final String peerId, final int[] remoteSamples, final String remoteAddress) {
        if (remoteSamples == null || remoteSamples.length == 0) {
            return;
        }
        final int[] sorted = remoteSamples.clone();
        java.util.Arrays.sort(sorted);
        final int median = sorted[sorted.length / 2];
        final int from = Math.max(1, median - TARGET_WIDTH_PER_SAMPLE * sorted.length);
        final int to = Math.min(65535, median + TARGET_WIDTH_PER_SAMPLE * sorted.length);
        final int[] targets = new int[to - from + 1];
        for (int i = 0; i < targets.length; i++) {
            targets[i] = from + i;
        }
        LOGGER.info(
                "Peer {} reported {} STUN samples, predicting ports [{}, {}]",
                peerId,
                sorted.length,
                from,
                to);

        final PeerPunchState state =
                states.computeIfAbsent(peerId, id -> new PeerPunchState(id));
        state.remoteTargets.set(targets);
        if (remoteAddress != null) {
            state.remoteAddress.set(remoteAddress);
        }
    }

    /** 对端发来了"自己用于通信的本地端口"。收到后构造 UdpLink 并对外广播。 */
    public void onPunchPort(final String peerId, final int remotePunchPort) {
        final PeerPunchState state = states.get(peerId);
        if (state == null) {
            return;
        }
        if (state.localChannel == null || state.localPunchPort < 0) {
            state.remotePunchPort.set(remotePunchPort);
            return;
        }
        buildAndStoreLink(peerId, state, remotePunchPort);
    }

    /** 拿到所有已就绪的对端链路。 */
    public Map<String, UdpLink> readyLinks() {
        final Map<String, UdpLink> ready = new HashMap<>();
        for (Map.Entry<String, Optional<UdpLink>> entry : links.entrySet()) {
            entry.getValue().ifPresent(link -> ready.put(entry.getKey(), link));
        }
        return ready;
    }

    /** 关掉某对端的所有本地资源。 */
    public void drop(final String peerId) {
        final PeerPunchState state = states.remove(peerId);
        if (state != null) {
            state.close();
        }
        links.computeIfPresent(
                peerId,
                (k, v) -> {
                    v.ifPresent(UdpLink::close);
                    return Optional.empty();
                });
        links.remove(peerId);
    }

    /** 关掉全部。 */
    public void close() {
        for (String peerId : new ArrayList<>(states.keySet())) {
            drop(peerId);
        }
    }

    /** 对一个对端启动一整轮打洞：发 STUN 样本给对方；本地采集样本并发出。 */
    public void startWith(final String peerId) {
        if (stunServers.isEmpty()) {
            LOGGER.info("No STUN servers configured, skipping UDP punch for {}", peerId);
            return;
        }
        final PeerPunchState state =
                states.computeIfAbsent(peerId, id -> new PeerPunchState(id));
        if (state.started) {
            return;
        }
        state.started = true;

        final Thread sampler =
                new Thread(
                        () -> {
                            try {
                                sampleAndPublish(peerId, state);
                            } catch (Exception e) {
                                LOGGER.warn("STUN sampling failed for {}: {}", peerId, e.getMessage());
                            }
                        },
                        "elink-punch-sample-" + peerId);
        sampler.setDaemon(true);
        sampler.start();
    }

    private void sampleAndPublish(final String peerId, final PeerPunchState state) throws IOException {
        final HolePuncher puncher = new HolePuncher(8);
        try {
            final List<HolePuncher.MappingSample> samples = puncher.observe(stunServers, 1500);
            final int[] samplePorts = new int[samples.size()];
            for (int i = 0; i < samples.size(); i++) {
                samplePorts[i] = samples.get(i).mappedPort();
            }

            final SignalMessage msg = SignalMessage.of(SignalMessage.Type.STUN_SAMPLES, roomCode, installId, displayName);
            msg.to = peerId;
            msg.stunSamples = samplePorts;
            signalling.publish(msg);

            startScanning(peerId, state, puncher);
        } catch (Exception e) {
            puncher.close();
            throw e;
        }
    }

    private void startScanning(
            final String peerId, final PeerPunchState state, final HolePuncher puncher) {
        final Thread waiter =
                new Thread(
                        () -> {
                            try {
                                final long deadline = System.currentTimeMillis() + PUNCH_DURATION_MS;
                                int[] targets;
                                String remote;
                                while (System.currentTimeMillis() < deadline) {
                                    targets = state.remoteTargets.get();
                                    remote = state.remoteAddress.get();
                                    if (targets != null && remote != null) {
                                        break;
                                    }
                                    Thread.sleep(50);
                                }
                                targets = state.remoteTargets.get();
                                remote = state.remoteAddress.get();
                                if (targets == null || remote == null) {
                                    LOGGER.info("Gave up punching {}: never saw their STUN samples", peerId);
                                    puncher.close();
                                    return;
                                }
                                final InetAddress target = InetAddress.getByName(remote);
                                state.remoteAddressForLink.set(target);
                                final Optional<HolePuncher.Path> hit =
                                        puncher.punch(
                                                target,
                                                targets,
                                                roomToken,
                                                PUNCH_DURATION_MS,
                                                PUNCH_RATE_PER_SECOND,
                                                new HolePuncher.Listener() {
                                                    @Override
                                                    public void onProgress(String status) {
                                                        LOGGER.debug("[punch {}] {}", peerId, status);
                                                    }

                                                    @Override
                                                    public void onHit(HolePuncher.Path path) {
                                                        onLocalHit(peerId, state, path);
                                                    }

                                                    @Override
                                                    public void onGiveUp(String reason) {
                                                        LOGGER.info("[punch {}] gave up: {}", peerId, reason);
                                                    }
                                                });
                                if (hit.isPresent()) {
                                    onLocalHit(peerId, state, hit.get());
                                }
                            } catch (Exception e) {
                                LOGGER.warn("Punch loop failed for {}: {}", peerId, e.getMessage());
                            }
                        },
                        "elink-punch-wait-" + peerId);
        waiter.setDaemon(true);
        waiter.start();
    }

    /** 命中了！记下本地 socket 与端口，发 PUNCH_PORT 给对端，并尝试构造链路。 */
    private void onLocalHit(final String peerId, final PeerPunchState state, final HolePuncher.Path path) {
        try {
            final DatagramChannel keep = path.channel();
            state.localChannel = keep;
            state.localPunchPort = ((InetSocketAddress) keep.getLocalAddress()).getPort();

            final SignalMessage msg = SignalMessage.of(SignalMessage.Type.PUNCH_PORT, roomCode, installId, displayName);
            msg.to = peerId;
            msg.punchPort = state.localPunchPort;
            try {
                signalling.publish(msg);
            } catch (Exception e) {
                LOGGER.warn("Failed to publish PUNCH_PORT to {}: {}", peerId, e.getMessage());
            }
            final Integer remotePort = state.remotePunchPort.get();
            if (remotePort != null) {
                buildAndStoreLink(peerId, state, remotePort);
            }
        } catch (IOException e) {
            LOGGER.warn("Recording the hit failed for {}: {}", peerId, e.getMessage());
        }
    }

    /** 双方端口都已知，构造 UdpLink 并存储。 */
    private void buildAndStoreLink(final String peerId, final PeerPunchState state, final int remotePort) {
        if (state.localChannel == null || state.localPunchPort < 0) {
            return;
        }
        final InetAddress remoteAddress = state.remoteAddressForLink.get();
        if (remoteAddress == null) {
            return;
        }
        final InetSocketAddress remote = new InetSocketAddress(remoteAddress, remotePort);
        final UdpLink link = new UdpLink(state.localChannel, remote, roomToken, new UdpLink.Listener() {
            @Override
            public void onData(byte[] payload) {
                if (state.coordinatorListener != null) {
                    state.coordinatorListener.onUdpPayload(peerId, payload);
                }
            }

            @Override
            public void onClosed(String reason) {
                LOGGER.info("UDP link with {} closed: {}", peerId, reason);
            }
        });
        link.start();
        links.put(peerId, Optional.of(link));
        LOGGER.info("UDP link with {} ready on local {} <-> remote {}:{}",
                peerId, state.localPunchPort, remoteAddress.getHostAddress(), remotePort);
    }

    /** 单个对端的状态：本地通道、端口、对端样本与地址、协商端口。 */
    private static final class PeerPunchState {
        final String peerId;
        final AtomicReference<int[]> remoteTargets = new AtomicReference<>();
        final AtomicReference<String> remoteAddress = new AtomicReference<>();
        final AtomicReference<InetAddress> remoteAddressForLink = new AtomicReference<>();
        final AtomicReference<Integer> remotePunchPort = new AtomicReference<>();
        DatagramChannel localChannel;
        int localPunchPort = -1;
        boolean started;
        LinkListener coordinatorListener;

        PeerPunchState(String peerId) {
            this.peerId = peerId;
        }

        void close() {
            if (localChannel != null) {
                try {
                    localChannel.close();
                } catch (IOException ignored) {
                }
                localChannel = null;
            }
        }
    }
}
