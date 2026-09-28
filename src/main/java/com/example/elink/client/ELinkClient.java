package com.example.elink.client;

import com.example.elink.ELink;
import com.example.elink.platform.Platform;
import com.example.elink.ui.ELinkTitleScreen;
import com.example.elink.ui.TitleOverlay;
import net.minecraft.client.Minecraft;

/**
 * Client only bridge.
 *
 * <p>The "Open to LAN" port exists only in a client process, so the common code is told about it from
 * here. No loader entrypoint is needed any more: the caller guards the call with
 * {@code Platform.isClient()}, and a dedicated server never loads this class.
 */
public final class ELinkClient {
    private ELinkClient() {}

    /** Hands the port of the shared world over to the common code. */
    public static void install() {
        ELink.setLanPortProvider(ELinkClient::lanPort);
        // Register the title-screen button on every supported loader (Fabric / Forge).
        TitleOverlay.register();
        // Wire up the UI opener so /elink ui can be invoked from the chat on the client.
        Platform.setUiOpener(() -> Minecraft.getInstance().execute(ELinkTitleScreen::open));
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
