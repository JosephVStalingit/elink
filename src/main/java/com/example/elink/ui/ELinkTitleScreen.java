package com.example.elink.ui;

import com.example.elink.ELink;
import com.example.elink.config.ELinkConfig;
import com.example.elink.identity.PlayerIdentity;
import com.example.elink.platform.Platform;
import com.example.elink.tunnel.TunnelSession;
import java.util.Optional;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The pixel-art control centre for the 易联 mod.
 *
 * <p>Reachable from the main menu (the {@link TitleOverlay} draws a button on top of Minecraft's
 * title screen) or directly with {@code /elink ui}. Does not replace the vanilla title screen —
 * that would surprise players who only want to play single-player — it sits next to it.
 */
public final class ELinkTitleScreen extends Screen {
    private static final Logger LOGGER = LoggerFactory.getLogger("elink/ui");

    /** Texture location of the mod icon; the file ships at {@code assets/elink/textures/gui/icon.png}. */
    private static final ResourceLocation ICON = makeIconLocation();

    /**
     * The {@code ResourceLocation} constructor was made private in 1.21; the factory method
     * {@code fromNamespaceAndPath} is the supported replacement on every supported version. The
     * fallback exists because Stonecutter compiles the same source for both 1.20.1 and 1.21.1.
     */
    private static ResourceLocation makeIconLocation() {
        try {
            // 1.21.x and later: public factory method
            return (ResourceLocation) ResourceLocation.class
                    .getMethod("fromNamespaceAndPath", String.class, String.class)
                    .invoke(null, ELink.MOD_ID, "textures/gui/icon.png");
        } catch (Throwable ignored) {
            // 1.20.x: public two-arg constructor
            try {
                return ResourceLocation.class
                        .getDeclaredConstructor(String.class, String.class)
                        .newInstance(ELink.MOD_ID, "textures/gui/icon.png");
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException("Cannot create a ResourceLocation for the ELink icon", e);
            }
        }
    }

    /** Sprite dimensions for the on-screen icon (downscaled from the 2048×2048 source). */
    private static final int ICON_BOX = 64;

    /** The parent screen, usually Minecraft's title screen. {@code null} when invoked via command. */
    private final Screen parent;

    /** Wall-clock ticks since the screen opened — drives the small "scanning" animation. */
    private long openTicks = 0;

    public ELinkTitleScreen(final Screen parent) {
        super(Component.literal("易联 — ELink"));
        this.parent = parent;
    }

    @Override
    public void tick() {
        super.tick();
        openTicks++;
    }

    @Override
    protected void init() {
        super.init();
        final int buttonWidth = 120;
        final int buttonHeight = 20;
        final int gap = 4;
        final int totalWidth = buttonWidth * 3 + gap * 2;
        final int startX = (this.width - totalWidth) / 2;
        final int y = this.height - 56;
        this.addRenderableWidget(Button.builder(
                Component.literal("▶ /elink host"),
                b -> runCommand("elink host"))
                .bounds(startX, y, buttonWidth, buttonHeight)
                .build());
        this.addRenderableWidget(Button.builder(
                Component.literal("▶ /elink join"),
                b -> runCommand("elink join"))
                .bounds(startX + buttonWidth + gap, y, buttonWidth, buttonHeight)
                .build());
        this.addRenderableWidget(Button.builder(
                Component.literal("✕ /elink leave"),
                b -> runCommand("elink leave"))
                .bounds(startX + (buttonWidth + gap) * 2, y, buttonWidth, buttonHeight)
                .build());
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(this.parent);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void render(final GuiGraphics graphics, final int mouseX, final int mouseY, final float partialTick) {
        renderBackdrop(graphics);
        final int cardMargin = 24;
        final int cardX = cardMargin;
        final int cardY = cardMargin;
        final int cardW = this.width - cardMargin * 2;
        final int cardH = this.height - cardMargin * 2;
        renderHeroCard(graphics, cardX, cardY, cardW, cardH);
        final int panelTop = cardY + ICON_BOX + 56;
        final int panelBottom = cardY + cardH - 90;
        final int panelH = panelBottom - panelTop;
        final int panelW = (cardW - 40) / 2;
        renderStatusPanel(graphics, cardX + 16, panelTop, panelW, panelH);
        renderInfoPanel(graphics, cardX + 24 + panelW, panelTop, panelW, panelH);
        super.render(graphics, mouseX, mouseY, partialTick);
        renderFooter(graphics);
    }

    private void renderBackdrop(final GuiGraphics graphics) {
        graphics.fill(0, 0, this.width, this.height, ELinkPalette.VOID);
        final int bandHeight = 80;
        final int bandY = (int) ((openTicks * 2L) % (this.height + bandHeight)) - bandHeight;
        PixelShapes.fillGradientV(
                graphics,
                0,
                bandY,
                this.width,
                bandHeight,
                ELinkPalette.SCANLINE,
                ELinkPalette.VOID);
        PixelShapes.scanlines(graphics, 0, 0, this.width, this.height, ELinkPalette.SCANLINE);
    }

    private void renderHeroCard(final GuiGraphics graphics, final int x, final int y, final int w, final int h) {
        PixelShapes.fill(graphics, x, y, w, h, ELinkPalette.DEEP);
        PixelShapes.doubleOutline(graphics, x, y, w, h, ELinkPalette.OUTER);
        PixelShapes.fill(graphics, x + 1, y + 1, w - 2, 2, ELinkPalette.ACCENT);

        final int iconX = x + 20;
        final int iconY = y + 18;
        PixelShapes.fill(graphics, iconX - 4, iconY - 4, ICON_BOX + 8, ICON_BOX + 8, ELinkPalette.SURFACE);
        PixelShapes.outline(graphics, iconX - 4, iconY - 4, ICON_BOX + 8, ICON_BOX + 8, ELinkPalette.OUTER);
        graphics.blit(ICON, iconX, iconY, 0, 0, ICON_BOX, ICON_BOX, ICON_BOX, ICON_BOX);

        final int textX = iconX + ICON_BOX + 16;
        final int textY = iconY + 4;
        PixelFont.hero(graphics, "易 联  /  E L I N K", textX, textY, ELinkPalette.HERO);
        PixelFont.body(
                graphics,
                "P2P multiplayer without a relay",
                textX,
                textY + PixelFont.TITLE_HEIGHT,
                ELinkPalette.ACCENT_DIM);

        renderChip(graphics, textX, textY + PixelFont.TITLE_HEIGHT + 18, "P2P", ELinkPalette.ACCENT);
        renderChip(graphics, textX + 44, textY + PixelFont.TITLE_HEIGHT + 18, "WebRTC", ELinkPalette.ACCENT);
        renderChip(
                graphics,
                textX + 100,
                textY + PixelFont.TITLE_HEIGHT + 18,
                "AES-256-GCM",
                ELinkPalette.ACCENT);

        final String versionTag = "v" + Platform.modVersion(ELink.MOD_ID) + " · MC " + SharedConstants.getCurrentVersion().getName();
        final int tagWidth = PixelFont.font().width(versionTag);
        PixelFont.body(
                graphics,
                versionTag,
                x + w - tagWidth - 12,
                y + 8,
                ELinkPalette.ACCENT_DIM);
    }

    private void renderChip(final GuiGraphics graphics, final int x, final int y, final String label, final int color) {
        final int w = PixelFont.font().width(label) + 8;
        final int h = 12;
        PixelShapes.fill(graphics, x, y, w, h, ELinkPalette.SURFACE);
        PixelShapes.outline(graphics, x, y, w, h, color);
        PixelFont.body(graphics, label, x + 4, y + 1, color);
    }

    private void renderStatusPanel(
            final GuiGraphics graphics, final int x, final int y, final int w, final int h) {
        PixelShapes.fill(graphics, x, y, w, h, ELinkPalette.SURFACE);
        PixelShapes.outline(graphics, x, y, w, h, ELinkPalette.BORDER);

        PixelFont.heading(graphics, "STATUS", x + 8, y + 6, ELinkPalette.HERO);
        PixelShapes.dottedDivider(graphics, x + 8, y + 22, w - 16, ELinkPalette.BORDER);

        final TunnelSession tunnel = ELink.tunnel();
        final ELinkConfig config = ELink.config();
        int rowY = y + 30;
        final int lineStep = PixelFont.LINE_HEIGHT + 2;

        if (tunnel == null || !tunnel.isActive()) {
            drawStatusRow(graphics, x + 8, rowY, w - 16, ELinkPalette.ACCENT_DIM, "Tunnel", "idle");
            rowY += lineStep;
            drawStatusRow(graphics, x + 8, rowY, w - 16, ELinkPalette.ACCENT_DIM, "Role", "—");
            rowY += lineStep;
            drawStatusRow(graphics, x + 8, rowY, w - 16, ELinkPalette.ACCENT_DIM, "Local port", "—");
            rowY += lineStep;
            drawStatusRow(graphics, x + 8, rowY, w - 16, ELinkPalette.ACCENT_DIM, "Peers", "0");
        } else if (tunnel.isHosting()) {
            drawStatusRow(graphics, x + 8, rowY, w - 16, ELinkPalette.GLOW, "Tunnel", "HOSTING");
            rowY += lineStep;
            drawStatusRow(graphics, x + 8, rowY, w - 16, ELinkPalette.GLOW, "Role", "share world");
            rowY += lineStep;
            drawStatusRow(graphics, x + 8, rowY, w - 16, ELinkPalette.ACCENT, "Target port", String.valueOf(tunnel.targetPort()));
            rowY += lineStep;
            drawStatusRow(graphics, x + 8, rowY, w - 16, ELinkPalette.ACCENT, "Peers", String.valueOf(tunnel.peerCount()));
        } else {
            drawStatusRow(graphics, x + 8, rowY, w - 16, ELinkPalette.GLOW, "Tunnel", "JOINING");
            rowY += lineStep;
            drawStatusRow(graphics, x + 8, rowY, w - 16, ELinkPalette.GLOW, "Role", "listen locally");
            rowY += lineStep;
            drawStatusRow(graphics, x + 8, rowY, w - 16, ELinkPalette.ACCENT, "Local port", String.valueOf(tunnel.localPort()));
            rowY += lineStep;
            drawStatusRow(graphics, x + 8, rowY, w - 16, ELinkPalette.ACCENT, "Peers", String.valueOf(tunnel.peerCount()));
        }
        rowY += lineStep;
        drawStatusRow(
                graphics,
                x + 8,
                rowY,
                w - 16,
                ELinkPalette.ACCENT_DIM,
                "Streams",
                String.valueOf(tunnel == null ? 0 : tunnel.streamCount()));

        rowY += lineStep + 6;
        PixelFont.body(graphics, "Room code", x + 8, rowY, ELinkPalette.ACCENT_DIM);
        rowY += PixelFont.LINE_HEIGHT;
        PixelShapes.fill(graphics, x + 8, rowY, w - 16, 16, ELinkPalette.TRACK);
        PixelShapes.outline(graphics, x + 8, rowY, w - 16, 16, ELinkPalette.BORDER);
        PixelFont.heading(graphics, config.getRoomCode(), x + 12, rowY + 2, ELinkPalette.HERO);

        rowY += 22;
        final String secret = config.getRoomSecret();
        final String secretDisplay = secret.isEmpty() ? "(none — /elink host to generate one)" : mask(secret);
        PixelFont.body(graphics, "Room secret", x + 8, rowY, ELinkPalette.ACCENT_DIM);
        rowY += PixelFont.LINE_HEIGHT;
        PixelShapes.fill(graphics, x + 8, rowY, w - 16, 16, ELinkPalette.TRACK);
        PixelShapes.outline(graphics, x + 8, rowY, w - 16, 16, ELinkPalette.BORDER);
        PixelFont.body(graphics, secretDisplay, x + 12, rowY + 2, secret.isEmpty() ? ELinkPalette.ALERT : ELinkPalette.ACCENT);
    }

    private void drawStatusRow(
            final GuiGraphics graphics, final int x, final int y, final int panelWidth, final int color, final String label, final String value) {
        PixelShapes.statusDot(graphics, x + 2, y + 5, color);
        PixelFont.body(graphics, label, x + 10, y, ELinkPalette.ACCENT_DIM);
        final int valueWidth = PixelFont.font().width(value);
        PixelFont.body(graphics, value, x + panelWidth - valueWidth - 4, y, color);
    }

    private void renderInfoPanel(
            final GuiGraphics graphics, final int x, final int y, final int w, final int h) {
        PixelShapes.fill(graphics, x, y, w, h, ELinkPalette.SURFACE);
        PixelShapes.outline(graphics, x, y, w, h, ELinkPalette.BORDER);

        PixelFont.heading(graphics, "PROFILE", x + 8, y + 6, ELinkPalette.HERO);
        PixelShapes.dottedDivider(graphics, x + 8, y + 22, w - 16, ELinkPalette.BORDER);

        int rowY = y + 30;
        final int lineStep = PixelFont.LINE_HEIGHT + 2;

        final Optional<PlayerIdentity> identity = ELink.identity().identity();
        if (identity.isPresent()) {
            final PlayerIdentity account = identity.get();
            PixelFont.body(graphics, "Signed in", x + 8, rowY, ELinkPalette.ACCENT_DIM);
            rowY += PixelFont.LINE_HEIGHT;
            PixelFont.heading(graphics, account.name(), x + 8, rowY, ELinkPalette.HERO);
            rowY += PixelFont.LINE_HEIGHT;
            PixelFont.body(graphics, account.dashedUuid(), x + 8, rowY, ELinkPalette.ACCENT_DIM);
        } else {
            PixelFont.body(graphics, "Signed in", x + 8, rowY, ELinkPalette.ACCENT_DIM);
            rowY += PixelFont.LINE_HEIGHT;
            PixelFont.heading(graphics, "(not signed in)", x + 8, rowY, ELinkPalette.ALERT);
            rowY += PixelFont.LINE_HEIGHT;
            PixelFont.body(
                    graphics, "use /elink login <email> <password>", x + 8, rowY, ELinkPalette.ACCENT_DIM);
        }
        rowY += lineStep + 4;

        final Set<String> friends = ELink.config().friendList();
        PixelFont.body(graphics, "Friend list", x + 8, rowY, ELinkPalette.ACCENT_DIM);
        rowY += PixelFont.LINE_HEIGHT;
        if (friends.isEmpty()) {
            PixelFont.body(graphics, "anyone with the room key may connect", x + 8, rowY, ELinkPalette.ACCENT);
            rowY += PixelFont.LINE_HEIGHT;
        } else {
            int shown = 0;
            for (String name : friends) {
                if (shown >= 3) {
                    PixelFont.body(
                            graphics,
                            "+ " + (friends.size() - shown) + " more — see /elink friends",
                            x + 8,
                            rowY,
                            ELinkPalette.ACCENT_DIM);
                    rowY += PixelFont.LINE_HEIGHT;
                    break;
                }
                PixelFont.body(graphics, "• " + name, x + 8, rowY, ELinkPalette.ACCENT);
                rowY += PixelFont.LINE_HEIGHT;
                shown++;
            }
        }
        rowY += 6;

        PixelShapes.dottedDivider(graphics, x + 8, rowY, w - 16, ELinkPalette.BORDER);
        rowY += 6;
        PixelFont.body(graphics, "Broker", x + 8, rowY, ELinkPalette.ACCENT_DIM);
        rowY += PixelFont.LINE_HEIGHT;
        PixelFont.body(graphics, ELink.config().getBrokerUri(), x + 8, rowY, ELinkPalette.ACCENT);
        rowY += lineStep;
        PixelFont.body(graphics, "Loader", x + 8, rowY, ELinkPalette.ACCENT_DIM);
        rowY += PixelFont.LINE_HEIGHT;
        PixelFont.body(graphics, loaderName(), x + 8, rowY, ELinkPalette.ACCENT);
    }

    private void renderFooter(final GuiGraphics graphics) {
        final String hint = "Press ESC to return · /elink <host|join|leave|status|login|friends>";
        final int w = PixelFont.font().width(hint);
        final int x = (this.width - w) / 2;
        final int y = this.height - 12;
        PixelFont.body(graphics, hint, x, y, ELinkPalette.ACCENT_DIM);
    }

    private void runCommand(final String suffix) {
        if (this.minecraft == null || this.minecraft.player == null) {
            return;
        }
        final String body = suffix.startsWith("elink") ? suffix.substring("elink".length()).trim() : suffix;
        final String command = "/elink" + (body.isEmpty() ? "" : " " + body);
        LOGGER.debug("Sending {} from the ELink UI", command);
        final String unsigned = command.startsWith("/") ? command.substring(1) : command;
        this.minecraft.player.connection.sendUnsignedCommand(unsigned);
    }

    private static String loaderName() {
        try {
            // The platform abstraction knows which loader compiled the active node; query it so
            // the UI stays correct if Forge / NeoForge get enabled later.
            return Platform.platformName();
        } catch (Throwable t) {
            return "unknown";
        }
    }

    private static String mask(final String secret) {
        if (secret.length() <= 8) {
            return secret;
        }
        return secret.substring(0, 4) + "…" + secret.substring(secret.length() - 4);
    }

    /** Opens this screen. Safe to call from anywhere that has access to the Minecraft client. */
    public static void open() {
        final net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        final Screen current = mc.screen;
        final Screen parent = current instanceof TitleScreen ? current : new TitleScreen();
        mc.setScreen(new ELinkTitleScreen(parent));
    }
}
