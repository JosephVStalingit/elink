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
        VERIFIED
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
        return builder.append(']').toString();
    }
}
