package com.example.mcp2p.net;

import com.example.mcp2p.config.McP2pConfig;
import com.example.mcp2p.identity.IdentityService;
import com.example.mcp2p.identity.PlayerIdentity;
import com.example.mcp2p.rtc.PeerSession;
import com.example.mcp2p.rtc.WebRtcEngine;
import com.example.mcp2p.signalling.SignalMessage;
import com.example.mcp2p.signalling.RoomSecret;
import com.example.mcp2p.signalling.SignallingClient;
import dev.onvoid.webrtc.RTCConfiguration;
import dev.onvoid.webrtc.RTCIceCandidate;
import dev.onvoid.webrtc.RTCIceServer;
import dev.onvoid.webrtc.RTCPeerConnectionState;
import dev.onvoid.webrtc.RTCSdpType;
import dev.onvoid.webrtc.RTCSessionDescription;
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
 * Joins a signalling room and keeps one {@link PeerSession} per peer found there.
 *
 * <p>The session is transport agnostic: it knows nothing about Minecraft, worlds or packets. It
 * hands out a reliable byte channel per peer and reports peers appearing and disappearing, which is
 * what the Minecraft integration will build on.
 *
 * <p>Negotiation roles are decided by comparing installation ids: the peer with the lower id
 * initiates, the other one answers. That is deterministic and needs no extra round trip, which keeps
 * the room free of two simultaneous offers.
 */
public final class P2PSession implements AutoCloseable, SignallingClient.Listener {
    private static final Logger LOGGER = LoggerFactory.getLogger("mcp2p/session");

    /** Events of the session, fired on WebRTC or Paho threads. */
    public interface Listener {
        /** The data channel of a peer became usable. */
        void onPeerReady(String peerId, String peerName);

        /** A peer left or its connection broke down. */
        void onPeerGone(String peerId, String reason);

        /** Data arrived from a peer; the buffer is a detached copy. */
        void onPeerData(String peerId, ByteBuffer data, boolean binary);

        /** A human readable status update, meant for logs or the player. */
        void onStatus(String status);
    }

    private final McP2pConfig config;
    private final String installId;
    private final String displayName;
    private final Listener listener;
    private final IdentityService identity;
    private final WebRtcEngine engine = new WebRtcEngine();
    private final Map<String, PeerSession> peers = new ConcurrentHashMap<>();
    private final Map<String, String> peerNames = new ConcurrentHashMap<>();
    private final Set<String> hostingPeers = ConcurrentHashMap.newKeySet();
    /** Peers whose account proof was accepted; a room without an allow list trusts everybody. */
    private final Set<String> verifiedPeers = ConcurrentHashMap.newKeySet();
    /** The random challenge ids handed out to peers. */
    private final Map<String, String> challenges = new ConcurrentHashMap<>();

    private SignallingClient signalling;
    private volatile boolean joined;
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

    private RTCConfiguration rtcConfiguration() {
        final RTCConfiguration configuration = new RTCConfiguration();
        final List<String> urls = config.iceServerUrls();
        if (!urls.isEmpty()) {
            final RTCIceServer server = new RTCIceServer();
            server.urls.addAll(urls);
            configuration.iceServers.add(server);
        }
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

    @Override
    public void onMessage(final SignalMessage message) {
        if (message.from.equals(installId)) {
            // Our own message, echoed back by the broker.
            return;
        }
        if (message.version != SignalMessage.VERSION) {
            LOGGER.warn(
                    "Ignoring {}: it speaks signalling protocol version {}",
                    message.from,
                    message.version);
            return;
        }
        if (!message.isFor(installId)) {
            return;
        }
        if (!accepts(message)) {
            LOGGER.warn(
                    "Ignoring a {} from {}: it does not prove that it knows the room secret",
                    message.type,
                    message.from);
            return;
        }
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
        if (installId.compareTo(peerId) < 0) {
            initiate(peerId);
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
                                peerListener(id)));
    }

    private void closePeer(final String peerId, final String reason) {
        hostingPeers.remove(peerId);
        verifiedPeers.remove(peerId);
        challenges.remove(peerId);
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
