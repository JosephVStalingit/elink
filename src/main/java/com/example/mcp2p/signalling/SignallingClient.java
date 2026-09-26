package com.example.mcp2p.signalling;

import com.example.mcp2p.config.McP2pConfig;
import java.nio.charset.StandardCharsets;
import org.eclipse.paho.client.mqttv3.IMqttActionListener;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.IMqttToken;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MQTT transport for the signalling phase.
 *
 * <p>The client subscribes to the room topic and its presence topic and hands every well-formed
 * message to {@link Listener}. Delivery is at least once (QoS 1), so the same message may in theory
 * arrive twice; the consumers are written to be idempotent. MQTT is only used to negotiate the
 * WebRTC session — once the data channel is open, nothing else travels through the broker.
 */
public final class SignallingClient implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("mcp2p/signalling");
    private static final int QOS_AT_LEAST_ONCE = 1;

    /** Callbacks of the signalling client; all of them run on Paho's worker thread. */
    public interface Listener {
        /** The broker connection is up; {@code reconnect} is false for the first connection. */
        void onConnected(boolean reconnect);

        /** The broker connection was lost; Paho keeps retrying in the background. */
        void onDisconnected(Throwable cause);

        /** A message from the room arrived. */
        void onMessage(SignalMessage message);
    }

    private final Listener listener;
    private final String roomTopic;
    private final String presenceTopic;
    private final MqttAsyncClient client;
    private volatile boolean closed;

    public SignallingClient(
            final McP2pConfig config, final String installId, final Listener listener)
            throws MqttException {
        this.listener = listener;
        this.roomTopic = config.roomTopic();
        this.presenceTopic = config.presenceTopic();
        this.client =
                new MqttAsyncClient(
                        config.getBrokerUri(), "mcp2p-" + installId, new MemoryPersistence());

        this.client.setCallback(
                new MqttCallbackExtended() {
                    @Override
                    public void connectComplete(final boolean reconnect, final String serverURI) {
                        LOGGER.info(
                                "Signalling broker {} {}",
                                serverURI,
                                reconnect ? "reconnected" : "connected");
                        subscribeToRoom();
                        listener.onConnected(reconnect);
                    }

                    @Override
                    public void connectionLost(final Throwable cause) {
                        LOGGER.warn(
                                "Signalling connection lost: {}",
                                cause == null ? "unknown reason" : cause.getMessage());
                        listener.onDisconnected(cause);
                    }

                    @Override
                    public void messageArrived(final String topic, final MqttMessage message) {
                        // Subscriptions carry their own listeners; this is only a safety net.
                    }

                    @Override
                    public void deliveryComplete(final IMqttDeliveryToken token) {
                        // Nothing to do: signals are fire and forget.
                    }
                });
    }

    /** Starts the connection. Returns immediately; completion is reported to the listener. */
    public void connect() throws MqttException {
        final MqttConnectOptions options = new MqttConnectOptions();
        options.setCleanSession(true);
        options.setAutomaticReconnect(true);
        options.setConnectionTimeout(10);
        options.setKeepAliveInterval(30);

        LOGGER.info(
                "Connecting to signalling broker {} as {} (room {})",
                client.getServerURI(),
                client.getClientId(),
                roomTopic);

        client.connect(options, null, new IMqttActionListener() {
            @Override
            public void onSuccess(final IMqttToken token) {
                // connectComplete() does the interesting part.
            }

            @Override
            public void onFailure(final IMqttToken token, final Throwable exception) {
                LOGGER.error("Could not reach the signalling broker", exception);
                listener.onDisconnected(exception);
            }
        });
    }

    /** Publishes a message to the room topic. */
    public void publish(final SignalMessage message) {
        if (closed) {
            return;
        }

        try {
            final byte[] payload = message.toJson().getBytes(StandardCharsets.UTF_8);
            client.publish(roomTopic, payload, QOS_AT_LEAST_ONCE, false);
        } catch (MqttException e) {
            LOGGER.warn("Could not publish a {} message", message.type, e);
        }
    }

    private void subscribeToRoom() {
        try {
            client.subscribe(roomTopic, QOS_AT_LEAST_ONCE, this::dispatch);
            client.subscribe(presenceTopic, QOS_AT_LEAST_ONCE, this::dispatch);
        } catch (MqttException e) {
            LOGGER.error("Could not subscribe to the room topic {}", roomTopic, e);
        }
    }

    private void dispatch(final String topic, final MqttMessage message) {
        try {
            final String json = new String(message.getPayload(), StandardCharsets.UTF_8);
            listener.onMessage(SignalMessage.parse(json));
        } catch (Exception e) {
            LOGGER.warn("Ignoring a malformed message on {}: {}", topic, e.getMessage());
        }
    }

    @Override
    public void close() {
        closed = true;
        try {
            if (client.isConnected()) {
                client.disconnect();
            }
            client.close();
        } catch (MqttException e) {
            LOGGER.debug("Error while closing the signalling client", e);
        }
    }
}
