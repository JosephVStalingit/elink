package com.example.mcp2p.tunnel;

import com.example.mcp2p.net.P2PSession;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Turns the peer-to-peer data channels into a working LAN bridge.
 *
 * <p>Two roles:
 *
 * <ul>
 *   <li>{@code HOST} — the world owner. Incoming streams are connected to the local game port (the
 *       port "Open to LAN" created), so friends end up inside the real integrated server.
 *   <li>{@code GUEST} — a friend. A local TCP port is opened and everything that connects to it is
 *       tunnelled to the host, which is the address to use as "Direct connect" in the multiplayer
 *       screen.
 * </ul>
 *
 * <p>The tunnel never touches Minecraft state: it is plain socket plumbing, which keeps it usable
 * from the client, from a dedicated server and from tests.
 */
public final class TunnelSession implements P2PSession.Listener {
    /** The data channel's send buffer is drained below this many bytes before more data is queued. */
    public static final long MAX_BUFFERED_BYTES = 512 * 1024;

    private static final Logger LOGGER = LoggerFactory.getLogger("mcp2p/tunnel");

    private volatile P2PSession session;
    private final Map<String, TunnelMultiplexer> peers = new ConcurrentHashMap<>();
    private final AtomicBoolean active = new AtomicBoolean();

    private volatile boolean hosting;
    private volatile int targetPort;
    private volatile int localPort;
    private volatile String hostPeer;

    private ServerSocket serverSocket;
    private Thread acceptThread;

    /** Creates a detached tunnel; {@link #attach(P2PSession)} connects it to its session. */
    public TunnelSession() {}

    /**
     * Sets the session the tunnel sends through. It has to happen before the session joins, so that no
     * peer event can arrive while the tunnel is still detached.
     */
    public void attach(final P2PSession session) {
        this.session = session;
    }

    private P2PSession session() {
        final P2PSession current = session;
        if (current == null) {
            throw new IllegalStateException("The tunnel is not attached to a session yet");
        }
        return current;
    }

    /**
     * Hosts the world: incoming streams are forwarded to a local game port.
     *
     * @return a message for the player, either the result or the reason it failed
     */
    public synchronized String startHost(final int gamePort) {
        if (gamePort <= 0) {
            return "There is no game port to share yet";
        }
        if (session == null) {
            return "Connect to a room first";
        }

        stop();
        hosting = true;
        targetPort = gamePort;
        active.set(true);
        for (String peerId : session().connectedPeers()) {
            ensureMultiplexer(peerId);
        }

        LOGGER.info("Hosting: incoming tunnel streams go to 127.0.0.1:{}", gamePort);
        return "Sharing the world on 127.0.0.1:" + gamePort;
    }

    /**
     * Joins a world: opens the local port that players connect to.
     *
     * @return a message for the player, either the result or the reason it failed
     */
    public synchronized String startGuest(final int port) {
        if (port < 0 || port > 65535) {
            return "Invalid local port " + port;
        }
        if (session == null) {
            return "Connect to a room first";
        }

        stop();
        hosting = false;
        try {
            final ServerSocket socket = new ServerSocket();
            socket.setReuseAddress(true);
            // A port of 0 asks the operating system for a free one, so joining never depends on a hard
            // coded number being available. The real port is picked up here and advertised below.
            socket.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
            serverSocket = socket;
            localPort = socket.getLocalPort();
        } catch (IOException e) {
            serverSocket = null;
            LOGGER.warn("Could not listen on 127.0.0.1:{}: {}", port, e.getMessage());
            return "Could not listen on 127.0.0.1:" + port + " (" + e.getMessage() + ")";
        }

        active.set(true);
        acceptThread = new Thread(this::acceptLoop, "mcp2p-tunnel-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
        for (String peerId : session().connectedPeers()) {
            ensureMultiplexer(peerId);
        }

        LOGGER.info("Tunnelling 127.0.0.1:{} into the room", localPort);
        return "Connect to 127.0.0.1:"
                + localPort
                + " once the host is in the room"
                + (port == 0 ? " (a free port was picked automatically)" : "");
    }

    /** Closes every stream and the local listener. */
    public synchronized void stop() {
        active.set(false);

        final ServerSocket socket = serverSocket;
        serverSocket = null;
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException e) {
                LOGGER.debug("Closing the tunnel listener: {}", e.getMessage());
            }
        }

        for (TunnelMultiplexer multiplexer : peers.values()) {
            multiplexer.close();
        }
        peers.clear();
        hostPeer = null;
        hosting = false;
        targetPort = 0;
        localPort = 0;
    }

    public boolean isActive() {
        return active.get();
    }

    public boolean isHosting() {
        return active.get() && hosting;
    }

    /** @return the local game port streams are forwarded to while hosting, otherwise {@code 0} */
    public int targetPort() {
        return targetPort;
    }

    /** @return the local port players connect to while joining, otherwise {@code 0} */
    public int localPort() {
        return localPort;
    }

    /** @return how many peers currently have a tunnel */
    public int peerCount() {
        return peers.size();
    }

    /** @return how many TCP connections are currently tunnelled */
    public int streamCount() {
        int count = 0;
        for (TunnelMultiplexer multiplexer : peers.values()) {
            count += multiplexer.streamCount();
        }
        return count;
    }

    void sendTo(final String peerId, final ByteBuffer frame) {
        if (!session().send(peerId, frame, true)) {
            LOGGER.debug("Dropped a tunnel frame for {}: it is not connected", peerId);
        }
    }

    long bufferedAmount(final String peerId) {
        return session().bufferedAmount(peerId);
    }

    @Override
    public void onPeerReady(final String peerId, final String peerName) {
        if (active.get()) {
            ensureMultiplexer(peerId);
            LOGGER.info("Tunnel with {} is ready", peerName);
        }
    }

    @Override
    public void onPeerGone(final String peerId, final String reason) {
        final TunnelMultiplexer multiplexer = peers.remove(peerId);
        if (multiplexer != null) {
            multiplexer.close();
        }
        if (peerId.equals(hostPeer)) {
            hostPeer = null;
        }
        LOGGER.info("Tunnel with {} closed: {}", peerId, reason);
    }

    @Override
    public void onPeerData(final String peerId, final ByteBuffer data, final boolean binary) {
        final TunnelMultiplexer multiplexer = peers.get(peerId);
        if (multiplexer != null) {
            multiplexer.handleFrame(data);
        }
    }

    @Override
    public void onStatus(final String status) {
        LOGGER.debug("{}", status);
    }

    private TunnelMultiplexer ensureMultiplexer(final String peerId) {
        return peers.computeIfAbsent(
                peerId,
                id ->
                        new TunnelMultiplexer(
                                id,
                                hosting ? TunnelMultiplexer.Role.HOST : TunnelMultiplexer.Role.GUEST,
                                this));
    }

    private void acceptLoop() {
        while (active.get()) {
            final ServerSocket listener = serverSocket;
            if (listener == null || listener.isClosed()) {
                return;
            }

            try {
                final Socket socket = listener.accept();
                socket.setTcpNoDelay(true);

                final String peerId = chooseHostPeer();
                if (peerId == null) {
                    LOGGER.info(
                            "A connection was made on 127.0.0.1:{} but nobody is sharing a world yet",
                            localPort);
                    socket.close();
                    continue;
                }

                ensureMultiplexer(peerId).openLocalStream(socket);
            } catch (IOException e) {
                if (active.get()) {
                    LOGGER.warn("The tunnel listener stopped accepting: {}", e.getMessage());
                }
                return;
            }
        }
    }

    /** @return the peer to tunnel to: one that announced hosting, otherwise the first ready peer */
    private String chooseHostPeer() {
        final String selected = hostPeer;
        if (selected != null && session().isPeerReady(selected)) {
            return selected;
        }

        final List<String> ready = session().connectedPeers();
        for (String peerId : ready) {
            if (session().isHosting(peerId)) {
                hostPeer = peerId;
                return peerId;
            }
        }

        if (ready.isEmpty()) {
            return null;
        }
        hostPeer = ready.get(0);
        return hostPeer;
    }
}
