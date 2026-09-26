package com.example.mcp2p.client;

import com.example.mcp2p.McP2p;
import net.minecraft.client.Minecraft;

/**
 * Client only bridge.
 *
 * <p>The "Open to LAN" port exists only in a client process, so the common code is told about it from
 * here. No loader entrypoint is needed any more: the caller guards the call with
 * {@code Platform.isClient()}, and a dedicated server never loads this class.
 */
public final class McP2pClient {
    private McP2pClient() {}

    /** Hands the port of the shared world over to the common code. */
    public static void install() {
        McP2p.setLanPortProvider(McP2pClient::lanPort);
    }

    /** @return the integrated server's port, or {@code -1} while no world is shared */
    private static int lanPort() {
        final Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.getSingleplayerServer() == null) {
            return -1;
        }
        final int port = minecraft.getSingleplayerServer().getPort();
        return port > 0 ? port : -1;
    }
}
