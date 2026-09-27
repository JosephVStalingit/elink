package com.example.elink.rtc;

import dev.onvoid.webrtc.CreateSessionDescriptionObserver;
import dev.onvoid.webrtc.PeerConnectionFactory;
import dev.onvoid.webrtc.PeerConnectionObserver;
import dev.onvoid.webrtc.RTCAnswerOptions;
import dev.onvoid.webrtc.RTCConfiguration;
import dev.onvoid.webrtc.RTCDataChannel;
import dev.onvoid.webrtc.RTCDataChannelBuffer;
import dev.onvoid.webrtc.RTCDataChannelInit;
import dev.onvoid.webrtc.RTCDataChannelObserver;
import dev.onvoid.webrtc.RTCDataChannelState;
import dev.onvoid.webrtc.RTCIceCandidate;
import dev.onvoid.webrtc.RTCIceConnectionState;
import dev.onvoid.webrtc.RTCOfferOptions;
import dev.onvoid.webrtc.RTCPeerConnection;
import dev.onvoid.webrtc.RTCPeerConnectionIceErrorEvent;
import dev.onvoid.webrtc.RTCPeerConnectionState;
import dev.onvoid.webrtc.RTCSessionDescription;
import dev.onvoid.webrtc.SetSessionDescriptionObserver;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One WebRTC connection to one remote peer, including the data channel that carries the tunnel.
 *
 * <p>Callbacks run on WebRTC's native threads, so the listener implementation must not block on the
 * game thread and must copy anything it wants to keep.
 */
public final class PeerSession implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("elink/rtc");

    /** Events of a peer session. */
    public interface Listener {
        /** A description was generated and applied locally, it is ready to be signalled. */
        void onLocalDescription(RTCSessionDescription description);

        /** A local ICE candidate was gathered and should be signalled. */
        void onLocalCandidate(RTCIceCandidate candidate);

        /** The data channel is usable. */
        void onDataChannelOpen();

        /** Data arrived on the data channel. The buffer is a detached copy. */
        void onMessage(ByteBuffer data, boolean binary);

        /** The overall connection state changed. */
        void onConnectionStateChanged(RTCPeerConnectionState state);

        /** The session cannot continue; the reason is meant for logs and the player. */
        void onClosed(String reason);
    }

    private final String peerId;
    private final String channelLabel;
    private final Listener listener;
    private final RTCPeerConnection connection;
    private final List<RTCIceCandidate> pendingCandidates = new ArrayList<>();

    /** 允许在打洞失败后自动重启 ICE 的次数（0 表示不重启）。 */
    private final int maxIceRestarts;

    /** 候选类型统计（host / srflx / prflx / relay），失败时用来判断"是不是需要 TURN"。 */
    private final Map<String, Integer> localCandidateTypes = new ConcurrentHashMap<>();
    private final Map<String, Integer> remoteCandidateTypes = new ConcurrentHashMap<>();

    /** STUN/TURN 报错的次数（例如服务器不可达），同样用于诊断。 */
    private final AtomicInteger iceErrors = new AtomicInteger();

    private RTCDataChannel dataChannel;
    private boolean remoteDescriptionSet;
    /** 本端是不是发起方；只有发起方负责在失败时重启 ICE，避免双方同时重启。 */
    private boolean initiator;
    /** 已经重启过几次。 */
    private int iceRestarts;
    private volatile boolean closed;

    public PeerSession(
            final PeerConnectionFactory factory,
            final RTCConfiguration configuration,
            final String peerId,
            final String channelLabel,
            final int maxIceRestarts,
            final Listener listener) {
        this.peerId = peerId;
        this.channelLabel = channelLabel;
        this.maxIceRestarts = maxIceRestarts;
        this.listener = listener;
        this.connection = factory.createPeerConnection(configuration, new PeerObserver());
        LOGGER.debug("Created a peer connection for {}", peerId);
    }

    /** Opens the data channel and creates the offer; used by the peer that initiates. */
    public void startAsInitiator() {
        initiator = true;
        final RTCDataChannelInit init = new RTCDataChannelInit();
        init.ordered = true;
        attachDataChannel(connection.createDataChannel(channelLabel, init));

        LOGGER.debug("Creating an offer for {}", peerId);
        connection.createOffer(new RTCOfferOptions(), new CreateDescriptionObserver());
    }

    /** Applies a remote offer and answers it. */
    public void handleOffer(final RTCSessionDescription offer) {
        setRemoteDescription(
                offer,
                () -> connection.createAnswer(new RTCAnswerOptions(), new CreateDescriptionObserver()));
    }

    /** Applies the remote answer to our offer. */
    public void handleAnswer(final RTCSessionDescription answer) {
        setRemoteDescription(answer, null);
    }

    /**
     * Adds a candidate from the remote peer. Candidates that arrive before the remote description are
     * buffered, because WebRTC rejects them otherwise.
     */
    public void addRemoteCandidate(final RTCIceCandidate candidate) {
        countCandidate(remoteCandidateTypes, candidate.sdp);
        synchronized (pendingCandidates) {
            if (!remoteDescriptionSet) {
                pendingCandidates.add(candidate);
                return;
            }
        }
        connection.addIceCandidate(candidate);
    }

    // ------------------------------------------------------------------
    // 打洞失败时的自救与诊断（对称 NAT 场景的关键部分）
    // ------------------------------------------------------------------

    /**
     * 打洞失败后重新收集候选并重发 offer。
     *
     * <p>对称 NAT 通常按固定顺序分配端口，重新收集一次有机会落到一条能通的路径上；在没配置 TURN 时，
     * 这是唯一还能自动尝试的手段。只由发起方执行，避免双方同时重启互相打断。
     *
     * @return {@code true} 表示确实发起了一次重启；调用方据此决定要不要放弃这条连接
     */
    private boolean restartIceBecauseOfFailure() {
        synchronized (this) {
            // 已经连上、已经关闭、不是发起方、或者重启次数用完，都不再重启
            if (closed || !initiator || isOpen() || iceRestarts >= maxIceRestarts) {
                return false;
            }
            iceRestarts++;
        }

        reportIceTrouble();
        LOGGER.warn("Restarting ICE for {} (attempt {}/{})", peerId, iceRestarts, maxIceRestarts);
        connection.restartIce();

        final RTCOfferOptions options = new RTCOfferOptions();
        options.iceRestart = true; // 关键：让新的 offer 带上一组全新的 ICE 凭证
        connection.createOffer(options, new CreateDescriptionObserver());
        return true;
    }

    /** 连不上时输出足够的诊断信息，而不是只丢一句"失败"。 */
    private void reportIceTrouble() {
        LOGGER.warn(
                "No working ICE path to {} yet: local candidates {}, remote candidates {}, STUN/TURN errors {}",
                peerId,
                localCandidateTypes,
                remoteCandidateTypes,
                iceErrors.get());

        // 双方都没有中继候选时，最可能的原因就是"两侧都在对称 NAT 后面"
        if (!localCandidateTypes.containsKey("relay") && !remoteCandidateTypes.containsKey("relay")) {
            LOGGER.warn(
                    "Neither side offers a relay candidate; if both peers sit behind a symmetric NAT, "
                            + "configure TURN in config/elink.properties (turn-servers, turn-username, turn-password)");
        }
    }

    /** 从候选的 SDP 文本里取出类型：host / srflx / prflx / relay。 */
    private static String candidateType(final String sdp) {
        if (sdp == null) {
            return "unknown";
        }
        final int index = sdp.indexOf(" typ ");
        if (index < 0) {
            return "unknown";
        }
        final String rest = sdp.substring(index + " typ ".length()).trim();
        final int end = rest.indexOf(' ');
        return end < 0 ? rest : rest.substring(0, end);
    }

    private static void countCandidate(final Map<String, Integer> into, final String sdp) {
        into.merge(candidateType(sdp), 1, Integer::sum);
    }

    /**
     * Sends data over the data channel, dropping it when the channel is not open yet.
     *
     * @param data a direct buffer positioned at the first byte to send
     * @param binary {@code true} for binary data, {@code false} for UTF-8 text
     */
    public void send(final ByteBuffer data, final boolean binary) {
        final RTCDataChannel channel = dataChannel;
        if (channel == null || channel.getState() != RTCDataChannelState.OPEN) {
            LOGGER.debug("Dropping a payload for {}: the data channel is not open", peerId);
            return;
        }
        // The native side reads the buffer asynchronously, so callers must not reuse it afterwards.
        channel.sendAsync(new RTCDataChannelBuffer(data, binary));
    }

    private void setRemoteDescription(
            final RTCSessionDescription description, final Runnable onSuccess) {
        connection.setRemoteDescription(
                description,
                new SetSessionDescriptionObserver() {
                    @Override
                    public void onSuccess() {
                        synchronized (pendingCandidates) {
                            remoteDescriptionSet = true;
                        }
                        flushPendingCandidates();
                        if (onSuccess != null) {
                            onSuccess.run();
                        }
                    }

                    @Override
                    public void onFailure(final String error) {
                        fail("Remote description rejected: " + error);
                    }
                });
    }

    private void flushPendingCandidates() {
        final List<RTCIceCandidate> pending;
        synchronized (pendingCandidates) {
            pending = new ArrayList<>(pendingCandidates);
            pendingCandidates.clear();
        }
        for (RTCIceCandidate candidate : pending) {
            connection.addIceCandidate(candidate);
        }
        if (!pending.isEmpty()) {
            LOGGER.debug("Applied {} buffered candidate(s) for {}", pending.size(), peerId);
        }
    }

    private void attachDataChannel(final RTCDataChannel channel) {
        this.dataChannel = channel;
        channel.registerObserver(
                new RTCDataChannelObserver() {
                    @Override
                    public void onBufferedAmountChange(final long sentDataSize) {
                        // Flow control is left to the native implementation for now.
                    }

                    @Override
                    public void onStateChange() {
                        final RTCDataChannelState state = channel.getState();
                        LOGGER.info("Data channel of {} is {}", peerId, state);
                        if (state == RTCDataChannelState.OPEN) {
                            listener.onDataChannelOpen();
                        }
                    }

                    @Override
                    public void onMessage(final RTCDataChannelBuffer buffer) {
                        // The native buffer is freed when this callback returns, so hand out a copy.
                        final ByteBuffer source = buffer.data;
                        final byte[] bytes = new byte[source.remaining()];
                        source.get(bytes);
                        listener.onMessage(ByteBuffer.wrap(bytes), buffer.binary);
                    }
                });
    }

    private void fail(final String reason) {
        LOGGER.warn("Peer session with {} failed: {}", peerId, reason);
        listener.onClosed(reason);
    }

    /** @return the installation id of the peer this session talks to */
    public String getPeerId() {
        return peerId;
    }

    /** @return {@code true} once the data channel can carry data */
    public boolean isOpen() {
        final RTCDataChannel channel = dataChannel;
        return channel != null && channel.getState() == RTCDataChannelState.OPEN;
    }

    /**
     * @return the bytes still queued in the data channel, or {@code 0} when there is none. The tunnel
     *     uses this to throttle itself instead of growing its queue without limit.
     */
    public long bufferedAmount() {
        final RTCDataChannel channel = dataChannel;
        return channel == null ? 0L : channel.getBufferedAmount();
    }

    @Override
    public void close() {
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
        }

        final RTCDataChannel channel = dataChannel;
        if (channel != null) {
            channel.unregisterObserver();
            channel.close();
            channel.dispose();
            dataChannel = null;
        }
        connection.close();
        LOGGER.debug("Closed the peer connection of {}", peerId);
    }

    /** Receives the callbacks of the underlying peer connection. */
    private final class PeerObserver implements PeerConnectionObserver {
        @Override
        public void onIceCandidate(final RTCIceCandidate candidate) {
            countCandidate(localCandidateTypes, candidate.sdp);
            listener.onLocalCandidate(candidate);
        }

        @Override
        public void onDataChannel(final RTCDataChannel channel) {
            attachDataChannel(channel);
        }

        @Override
        public void onIceConnectionChange(final RTCIceConnectionState state) {
            LOGGER.debug("ICE of {} is {}", peerId, state);
            if (state == RTCIceConnectionState.FAILED) {
                // 先尝试自动重启：对称 NAT 下换一组映射可能就通了
                restartIceBecauseOfFailure();
            }
        }

        @Override
        public void onIceCandidateError(final RTCPeerConnectionIceErrorEvent event) {
            iceErrors.incrementAndGet();
            LOGGER.warn(
                    "STUN/TURN error for {}: {} {} (server {})",
                    peerId,
                    event.getErrorCode(),
                    event.getErrorText(),
                    event.getUrl());
        }

        @Override
        public void onConnectionChange(final RTCPeerConnectionState state) {
            LOGGER.info("Peer connection of {} is {}", peerId, state);
            listener.onConnectionStateChanged(state);
            if (state == RTCPeerConnectionState.FAILED) {
                // 只有重启机会用尽（或本端不是发起方）才真正放弃这条连接
                if (!restartIceBecauseOfFailure()) {
                    fail("the connection could not be established");
                }
            }
        }
    }

    /** Applies the description WebRTC generated and hands it to the session for signalling. */
    private final class CreateDescriptionObserver implements CreateSessionDescriptionObserver {
        @Override
        public void onSuccess(final RTCSessionDescription description) {
            connection.setLocalDescription(
                    description,
                    new SetSessionDescriptionObserver() {
                        @Override
                        public void onSuccess() {
                            listener.onLocalDescription(description);
                        }

                        @Override
                        public void onFailure(final String error) {
                            fail("local description rejected: " + error);
                        }
                    });
        }

        @Override
        public void onFailure(final String error) {
            fail("could not create a session description: " + error);
        }
    }
}
