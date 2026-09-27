package com.example.mcp2p.signalling;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

/**
 * A single signalling message, serialised as JSON and carried by MQTT.
 *
 * <p>The fields are public and non-final on purpose: Gson fills them in by reflection, and the
 * message is a plain data transfer object. {@link #version} lets a future release reject peers that
 * speak an incompatible protocol instead of misinterpreting their messages.
 *
 * <p>See {@code docs/architecture.md} for the full protocol description.
 */
public final class SignalMessage {
    /** Protocol version spoken by this build. */
    public static final int VERSION = 1;

    /** What the message is about. */
    public enum Type {
        /** Announces a peer that joined the room, and answers other announcements. */
        HELLO,
        /** A peer is leaving, or the session is being torn down. */
        BYE,
        /** SDP offer, sent by the peer that initiates the WebRTC session. */
        OFFER,
        /** SDP answer, sent by the peer that accepts the session. */
        ANSWER,
        /** A single trickled ICE candidate. */
        CANDIDATE,
        /** The host asks a peer to prove that it owns the account it claims. */
        CHALLENGE,
        /** The peer answers a challenge after proving itself to the account service. */
        CHALLENGE_RESPONSE,
        /** The host confirms that a peer's proof was accepted. */
        VERIFIED,
        /**
         * Sender publishes a batch of "STUN 反射出的公网端口"样本，让对方据此预测要扫描的端口区间
         * （对称 NAT 下要靠这个推测对方可能使用的端口）。
         */
        STUN_SAMPLES,
        /**
         * 双方打洞命中后，互相告知"我自己用来通信的本地端口"，让对端把发送目标改成它——避免生日
         * 悖论扫描两端命中不同本地端口时，A 发到 B 的旧端口而 B 在新端口上收不到的问题。
         */
        PUNCH_PORT
    }

    private static final Gson GSON = new Gson();

    /** Protocol version of this message. */
    public int version = VERSION;

    public Type type;

    /** Room the message belongs to. */
    public String room;

    /** Stable identifier of the sending installation. */
    public String from;

    /** Display name of the sender. */
    public String fromName;

    /** Recipient installation id, {@code null} when addressed to the whole room. */
    public String to;

    /** SDP payload of an {@link Type#OFFER} or {@link Type#ANSWER} message. */
    public String sdp;

    /** Name of the {@code dev.onvoid.webrtc.RTCSdpType} that goes with {@link #sdp}. */
    public String sdpType;

    /** Media description the candidate belongs to. */
    public String sdpMid;

    /** Index of the media description inside the SDP. */
    public Integer sdpMLineIndex;

    /** Human readable reason of a {@link Type#BYE} message. */
    public String reason;

    /**
     * Set on {@link Type#HELLO} by a peer that shares a world, so that joining peers know which peer
     * to tunnel to. {@code null} on every other message.
     */
    public Boolean hosting;

    /**
     * Proof that the sender knows the room secret: {@code HMAC-SHA256(secret, room|from|type)} as
     * lowercase hex, or {@code null} while the room has no secret. See {@link RoomSecret}.
     */
    public String proof;

    /** The account id the sender claims, without dashes, or {@code null} without an account. */
    public String uuid;

    /** Set by a host that only negotiates after the peer proved its account. */
    public Boolean requiresVerification;

    /** The random id of a {@link Type#CHALLENGE} handshake, echoed back in the response. */
    public String challenge;

    /**
     * {@link Type#STUN_SAMPLES}：本机经过若干 STUN 服务器反射得到的公网端口列表，用来让对方推断
     * 我们接下来可能要使用的端口区间（对称 NAT 下，端口是按某种规律递增的）。
     */
    public int[] stunSamples;

    /**
     * {@link Type#PUNCH_PORT}：本机打洞命中后，用于承载 Minecraft 流量的本地 UDP 端口。
     * 对端收到后把发送目标改成这个端口，从而保证两端在同一对 socket 上收发。
     */
    public Integer punchPort;

    /** 对端的公网 IP（用 MQTT 信令递过去的字符串形式），打洞的目标地址就是它。 */
    public String punchAddress;

    /** Creates an empty message of the given type, ready to be filled in and published. */
    public static SignalMessage of(
            final Type type, final String room, final String from, final String fromName) {
        final SignalMessage message = new SignalMessage();
        message.type = type;
        message.room = room;
        message.from = from;
        message.fromName = fromName;
        return message;
    }

    /**
     * Parses a message received from the room.
     *
     * @throws JsonSyntaxException if the payload is not a signalling message
     */
    public static SignalMessage parse(final String json) throws JsonSyntaxException {
        final SignalMessage message = GSON.fromJson(json, SignalMessage.class);
        if (message == null || message.type == null || message.from == null) {
            throw new JsonSyntaxException("Not a signalling message: " + json);
        }
        return message;
    }

    public String toJson() {
        return GSON.toJson(this);
    }

    /** @return {@code true} when the message is either addressed to us or broadcast to the room */
    public boolean isFor(final String installId) {
        return to == null || to.equals(installId);
    }

    /** @return {@code true} for messages every peer in the room is supposed to read */
    public boolean isBroadcast() {
        return to == null;
    }

    @Override
    public String toString() {
        final StringBuilder builder = new StringBuilder("SignalMessage[").append(type);
        builder.append(" from=").append(from);
        if (to != null) {
            builder.append(" to=").append(to);
        }
        if (sdp != null) {
            builder.append(" sdp=").append(sdp.length()).append(" chars");
        }
        if (sdpMid != null) {
            builder.append(" candidate=").append(sdpMid).append('#').append(sdpMLineIndex);
        }
        if (stunSamples != null) {
            builder.append(" stunSamples=").append(stunSamples.length);
        }
        if (punchPort != null) {
            builder.append(" punchPort=").append(punchPort);
        }
        return builder.append(']').toString();
    }
}
