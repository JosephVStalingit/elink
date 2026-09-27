package com.example.elink.signalling;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The shared room secret and the proof that travels with every signalling message.
 *
 * <p>Without a secret anybody who knows (or guesses) a room code can subscribe to its MQTT topic on a
 * public broker and negotiate a WebRTC session with the peers inside. With a secret, every message
 * carries an HMAC over the room, the sender and the message type, and a peer that cannot produce it is
 * ignored before any WebRTC work happens.
 *
 * <p>The secret is a bearer token: whoever holds it can enter. It is meant to be handed to friends
 * through a private channel, and {@code /elink host} prints it together with the room code. Messages
 * inside an accepted room can still be forged by a peer that was let in — the threat model is "keep
 * strangers out", not "trust nobody inside".
 */
public final class RoomSecret {
    /** Length of a generated secret in hex characters (128 bit). */
    public static final int LENGTH = 32;

    private RoomSecret() {}

    /** @return a fresh random secret */
    public static String generate() {
        final byte[] bytes = new byte[LENGTH / 2];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /**
     * Computes the proof of one message.
     *
     * @return the lowercase hex HMAC, or {@code null} when no secret is configured
     */
    public static String proof(
            final String secret, final String room, final String from, final Object type) {
        if (secret == null || secret.isBlank()) {
            return null;
        }

        try {
            final Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            final String payload = room + '|' + from + '|' + type;
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", e);
        }
    }

    /** Compares proofs in constant time to keep timing out of the picture. */
    public static boolean matches(final String expected, final String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.US_ASCII),
                actual.getBytes(StandardCharsets.US_ASCII));
    }
}
