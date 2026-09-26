package com.example.mcp2p.tunnel;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Multiplexes the virtual TCP connections of one peer over that peer's data channel.
 *
 * <p>The host side reacts to {@link TunnelFrame#OPEN} frames by dialling its local game port, the
 * guest side creates streams for the connections that arrive on its listening port. After that both
 * sides only exchange {@link TunnelFrame#DATA} and {@link TunnelFrame#CLOSE} frames.
 */
final class TunnelMultiplexer {
    enum Role {
        /** The world owner: opens outgoing connections to its own game port. */
        HOST,
        /** A friend: opens streams for connections that arrive on its listening port. */
        GUEST
    }

    private static final Logger LOGGER = LoggerFactory.getLogger("mcp2p/tunnel");
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final long MAX_BACKPRESSURE_MS = 30_000;

    private final String peerId;
    private final Role role;
    private final TunnelSession session;
    private final Map<Integer, TunnelStream> streams = new ConcurrentHashMap<>();
    private final AtomicInteger nextStreamId = new AtomicInteger(1);
    private final AtomicBoolean closed = new AtomicBoolean();

    TunnelMultiplexer(final String peerId, final Role role, final TunnelSession session) {
        this.peerId = peerId;
        this.role = role;
        this.session = session;
    }

    int streamCount() {
        return streams.size();
    }

    /** Opens a stream towards the peer because a connection arrived on our listening port. */
    void openLocalStream(final Socket socket) {
        if (closed.get()) {
            closeQuietly(socket);
            return;
        }
        final int streamId = nextStreamId.getAndIncrement();
        final TunnelStream stream = new TunnelStream(streamId, socket, this);
        streams.put(streamId, stream);
        send(TunnelFrame.encode(streamId, TunnelFrame.OPEN));
        stream.start();
        LOGGER.info("Opened tunnel stream {} towards {}", streamId, peerId);
    }

    /** Handles one frame that arrived from the peer. */
    void handleFrame(final ByteBuffer frame) {
        if (closed.get() || !TunnelFrame.isValid(frame)) {
            return;
        }

        final int streamId = TunnelFrame.streamId(frame);
        switch (TunnelFrame.type(frame)) {
            case TunnelFrame.OPEN:
                acceptRemoteStream(streamId);
                break;
            case TunnelFrame.DATA:
                final TunnelStream stream = streams.get(streamId);
                if (stream == null) {
                    LOGGER.debug("Ignoring data for the unknown tunnel stream {}", streamId);
                    return;
                }
                final ByteBuffer payload = TunnelFrame.payload(frame);
                final byte[] bytes = new byte[payload.remaining()];
                payload.get(bytes);
                if (!stream.onRemoteData(bytes)) {
                    streams.remove(streamId, stream);
                }
                break;
            case TunnelFrame.CLOSE:
                final TunnelStream closing = streams.remove(streamId);
                if (closing != null) {
                    closing.close("the remote end closed it");
                }
                break;
            default:
                LOGGER.warn("Ignoring a frame of unknown type {}", TunnelFrame.type(frame));
                break;
        }
    }

    /** Sends a payload, splitting it into frames and waiting for the data channel to drain. */
    void sendData(final int streamId, final byte[] data, final int length) {
        int offset = 0;
        while (offset < length && !closed.get()) {
            final int chunkLength = Math.min(TunnelFrame.MAX_PAYLOAD, length - offset);
            if (!awaitSendCapacity()) {
                return;
            }
            send(TunnelFrame.encode(streamId, TunnelFrame.DATA, data, offset, chunkLength));
            offset += chunkLength;
        }
    }

    void send(final ByteBuffer frame) {
        session.sendTo(peerId, frame);
    }

    void removeStream(final TunnelStream stream) {
        streams.remove(stream.streamId(), stream);
    }

    void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        for (TunnelStream stream : streams.values()) {
            stream.close("the peer is gone");
        }
        streams.clear();
    }

    private void acceptRemoteStream(final int streamId) {
        final int port = session.targetPort();

        if (role != Role.HOST || port <= 0) {
            LOGGER.warn("{} asked for a stream although nothing is shared", peerId);
            send(TunnelFrame.encode(streamId, TunnelFrame.CLOSE));
            return;
        }

        try {
            final Socket socket = new Socket();
            socket.setTcpNoDelay(true);
            socket.connect(
                    new InetSocketAddress(InetAddress.getLoopbackAddress(), port), CONNECT_TIMEOUT_MS);
            final TunnelStream stream = new TunnelStream(streamId, socket, this);
            streams.put(streamId, stream);
            stream.start();
            LOGGER.info("{} joined through the tunnel (stream {})", peerId, streamId);
        } catch (IOException e) {
            LOGGER.warn("Could not reach 127.0.0.1:{} for {}: {}", port, peerId, e.getMessage());
            send(TunnelFrame.encode(streamId, TunnelFrame.CLOSE));
        }
    }

    /** @return {@code false} when the peer never drains its buffer and the tunnel should be dropped */
    private boolean awaitSendCapacity() {
        long waited = 0;
        while (!closed.get() && session.bufferedAmount(peerId) > TunnelSession.MAX_BUFFERED_BYTES) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            waited += 10;
            if (waited > MAX_BACKPRESSURE_MS) {
                LOGGER.warn("{} is not reading fast enough, closing the tunnel", peerId);
                close();
                return false;
            }
        }
        return !closed.get();
    }

    private static void closeQuietly(final Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // Nothing useful to do while shutting down.
        }
    }
}
