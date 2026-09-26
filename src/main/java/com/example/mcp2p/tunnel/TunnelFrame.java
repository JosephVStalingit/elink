package com.example.mcp2p.tunnel;

import java.nio.ByteBuffer;

/**
 * Wire format of the tunnel that multiplexes TCP connections over one WebRTC data channel.
 *
 * <p>Layout: {@code [streamId:int][type:byte][payload]}. One data channel message carries exactly one
 * frame, so no stream parsing is needed; ordering and reliability are provided by SCTP (the channel is
 * opened as ordered and reliable). Payloads larger than {@link #MAX_PAYLOAD} are split by the sender
 * into consecutive frames of the same stream, which is safe because the channel preserves order.
 */
public final class TunnelFrame {
    /** Asks the remote side to open a connection to its tunnel target. */
    public static final byte OPEN = 1;

    /** A chunk of the byte stream. */
    public static final byte DATA = 2;

    /** The stream is finished; the receiver closes its socket. */
    public static final byte CLOSE = 3;

    /**
     * Largest payload carried by one frame. Well below the SCTP message limit (which browsers/WebRTC
     * negotiate around 64 KiB) and a multiple of typical socket read sizes.
     */
    public static final int MAX_PAYLOAD = 16 * 1024;

    private static final int HEADER_SIZE = Integer.BYTES + 1;

    private TunnelFrame() {}

    /** Builds a frame with a payload slice, as a direct buffer ready for the data channel. */
    public static ByteBuffer encode(
            final int streamId, final byte type, final byte[] payload, final int offset, final int length) {
        final ByteBuffer frame = ByteBuffer.allocateDirect(HEADER_SIZE + length);
        frame.putInt(streamId).put(type);
        if (length > 0) {
            frame.put(payload, offset, length);
        }
        frame.flip();
        return frame;
    }

    /** Builds a frame without a payload. */
    public static ByteBuffer encode(final int streamId, final byte type) {
        return encode(streamId, type, null, 0, 0);
    }

    /** @return {@code true} when the buffer is long enough to hold a frame header */
    public static boolean isValid(final ByteBuffer frame) {
        return frame.remaining() >= HEADER_SIZE;
    }

    /** @return the stream the frame belongs to */
    public static int streamId(final ByteBuffer frame) {
        return frame.getInt(frame.position());
    }

    /** @return the frame type, see {@link #OPEN}, {@link #DATA} and {@link #CLOSE} */
    public static byte type(final ByteBuffer frame) {
        return frame.get(frame.position() + Integer.BYTES);
    }

    /** @return a view of the payload, positioned at its first byte */
    public static ByteBuffer payload(final ByteBuffer frame) {
        final ByteBuffer view = frame.duplicate();
        view.position(view.position() + HEADER_SIZE);
        return view.slice();
    }
}
