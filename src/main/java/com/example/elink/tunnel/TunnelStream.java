package com.example.elink.tunnel;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One virtual TCP connection: a real socket on this side, a stream id on the data channel.
 *
 * <p>Two threads serve a stream. The reader pulls bytes out of the socket and hands them to the
 * multiplexer, which applies back pressure by waiting for the data channel's send buffer to drain.
 * The writer takes chunks that arrived from the remote side and writes them to the socket, so slow
 * local sockets never block WebRTC's callback threads.
 *
 * <p>The queue towards the local socket is bounded: if the local side cannot keep up for a long time,
 * the stream is dropped instead of letting the queue grow without limit. The reader side needs no such
 * queue because back pressure on the sending peer throttles it.
 */
final class TunnelStream {
    private static final Logger LOGGER = LoggerFactory.getLogger("elink/tunnel");

    /** Marks the end of the inbound queue; never a real payload. */
    private static final byte[] POISON = new byte[0];

    private static final int MAX_QUEUED_CHUNKS = 1024;

    private final int streamId;
    private final Socket socket;
    private final TunnelMultiplexer owner;
    private final BlockingQueue<byte[]> inbound = new LinkedBlockingQueue<>(MAX_QUEUED_CHUNKS);
    private final AtomicBoolean closed = new AtomicBoolean();

    TunnelStream(final int streamId, final Socket socket, final TunnelMultiplexer owner) {
        this.streamId = streamId;
        this.socket = socket;
        this.owner = owner;
    }

    int streamId() {
        return streamId;
    }

    void start() {
        final Thread reader = new Thread(this::readLoop, "elink-tunnel-read-" + streamId);
        final Thread writer = new Thread(this::writeLoop, "elink-tunnel-write-" + streamId);
        reader.setDaemon(true);
        writer.setDaemon(true);
        reader.start();
        writer.start();
    }

    /**
     * Queues data that arrived from the remote peer.
     *
     * @return {@code false} when the stream had to be dropped because the local side is too slow
     */
    boolean onRemoteData(final byte[] data) {
        if (closed.get()) {
            return false;
        }
        if (!inbound.offer(data)) {
            LOGGER.warn("Tunnel stream {} is backed up, dropping it", streamId);
            close("the local socket cannot keep up");
            return false;
        }
        return true;
    }

    /** The remote side closed the stream; nothing else will arrive. */
    void onRemoteClose() {
        inbound.offer(POISON);
    }

    void close(final String reason) {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        inbound.offer(POISON);
        try {
            socket.close();
        } catch (IOException e) {
            LOGGER.debug("Tunnel stream {}: {}", streamId, e.getMessage());
        }
        owner.removeStream(this);
        LOGGER.debug("Tunnel stream {} closed: {}", streamId, reason);
    }

    private void readLoop() {
        final byte[] buffer = new byte[TunnelFrame.MAX_PAYLOAD];
        try (InputStream in = socket.getInputStream()) {
            int read;
            while (!closed.get() && (read = in.read(buffer)) >= 0) {
                if (read > 0) {
                    owner.sendData(streamId, buffer, read);
                }
            }
        } catch (IOException e) {
            LOGGER.debug("Tunnel stream {} read loop ended: {}", streamId, e.getMessage());
        }
        owner.send(TunnelFrame.encode(streamId, TunnelFrame.CLOSE));
        close("the local socket is finished");
    }

    private void writeLoop() {
        try (OutputStream out = socket.getOutputStream()) {
            while (!closed.get()) {
                final byte[] chunk = inbound.take();
                if (chunk == POISON) {
                    break;
                }
                out.write(chunk);
                if (inbound.isEmpty()) {
                    out.flush();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            LOGGER.debug("Tunnel stream {} write loop ended: {}", streamId, e.getMessage());
        }
        close("the write loop is finished");
    }
}
