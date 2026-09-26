package com.example.mcp2p.lan;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Makes a tunnel show up in the game's multiplayer list.
 *
 * <p>The vanilla client finds LAN worlds by listening on the multicast group {@code 224.0.2.60}, port
 * 4445, and reading payloads of the form {@code [MOTD]<text>[/MOTD][AD]<port>[/AD]}. The entry it shows
 * is the packet's source address plus that port, so a tunnel is advertised by sending exactly that
 * payload from the loopback address: the client then offers {@code 127.0.0.1:<local port>}, which is
 * where the tunnel accepts connections.
 *
 * <p>Loopback is used on purpose — advertising from the machine's LAN address would point players at
 * an address the tunnel does not listen on. The detector binds the wildcard address, so a loopback
 * datagram reaches it; that was checked against the client implementation of both 1.20.1 and 1.21.1,
 * neither of which filters the source address, and confirmed with a bound multicast socket.
 */
public final class LanAnnouncer implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("mcp2p/lan");

    /** Group the vanilla client listens on, and the port it binds. */
    private static final String GROUP = "224.0.2.60";
    private static final int PORT = 4445;

    /** The client drops entries after roughly two seconds without an update. */
    private static final long INTERVAL_MS = 1500;

    private final MulticastSocket socket;
    private final InetAddress loopback;

    private ScheduledExecutorService scheduler;

    public LanAnnouncer() throws IOException {
        this.socket = new MulticastSocket();
        this.socket.setTimeToLive(1);
        this.loopback = InetAddress.getLoopbackAddress();
    }

    /**
     * Starts announcing.
     *
     * @param portSupplier the local port of the tunnel, or a value {@code <= 0} to stay silent
     * @param motdSupplier the text shown in the server list
     */
    public void start(final IntSupplier portSupplier, final Supplier<String> motdSupplier) {
        scheduler =
                Executors.newSingleThreadScheduledExecutor(
                        runnable -> {
                            final Thread thread = new Thread(runnable, "mcp2p-lan-announcer");
                            thread.setDaemon(true);
                            return thread;
                        });
        scheduler.scheduleWithFixedDelay(
                () -> announce(portSupplier, motdSupplier), 0, INTERVAL_MS, TimeUnit.MILLISECONDS);
        LOGGER.debug("Announcing tunnels on 127.0.0.1:{}", PORT);
    }

    private void announce(final IntSupplier portSupplier, final Supplier<String> motdSupplier) {
        final int port = portSupplier.getAsInt();
        if (port <= 0 || port > 65535) {
            // No tunnel to advertise, or it is hosting: nothing for the server list.
            return;
        }

        final String payload = "[MOTD]" + sanitise(motdSupplier.get()) + "[/MOTD][AD]" + port + "[/AD]";
        final byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        try {
            socket.send(new DatagramPacket(bytes, bytes.length, loopback, PORT));
        } catch (IOException e) {
            LOGGER.debug("Could not announce the tunnel: {}", e.getMessage());
        }
    }

    /** Keeps the markers out of the text so that the client parses the payload correctly. */
    private static String sanitise(final String text) {
        final String cleaned = text.replace('[', '(').replace(']', ')');
        return cleaned.length() > 60 ? cleaned.substring(0, 60) : cleaned;
    }

    @Override
    public void close() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        socket.close();
    }
}
