package com.example.elink.ui;

import com.example.elink.ELink;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Cross-loader glue that puts a "易联" button onto Minecraft's title screen.
 *
 * <p>The three loaders the project supports expose different APIs for screen manipulation, so the
 * registration is split into Stonecutter branches — exactly one variant compiles per loader:
 *
 * <ul>
 *   <li><b>Fabric</b>: {@code ScreenEvents.AFTER_INIT}.</li>
 *   <li><b>Forge</b>: {@code ScreenEvent.Init.Post} (Forge 1.20.1+ also provides this).</li>
 *   <li><b>NeoForge</b>: same as Forge (NeoForge 1.21.1 inherits the API).</li>
 * </ul>
 *
 * <p>The button position, label and click behaviour live in {@link #buildButton(int, int)} so the
 * three implementations stay in lock-step.
 */
public final class TitleOverlay {
    private static final Logger LOGGER = LoggerFactory.getLogger("elink/ui");

    /** Width and height of the injected button. */
    private static final int BUTTON_W = 98;
    private static final int BUTTON_H = 20;

    private TitleOverlay() {}

    /** Public entry point; called once during mod initialisation from a loader-specific client hook. */
    public static void register() {
        /*? if fabric {*/
        registerFabric();
        /*?}*/
        /*? if forge {*/
        /*registerForge();
        *//*?}*/
        /*? if neoforge {*/
        /*registerNeoForge();
        *//*?}*/
    }

    /** Build a button instance with the same label and click handler on every loader. */
    private static Button buildButton(final int x, final int y) {
        return Button.builder(
                Component.literal("易联  ELink"),
                button -> ELinkTitleScreen.open())
                .bounds(x, y, BUTTON_W, BUTTON_H)
                .build();
    }

    /**
     * Append a widget to a screen whose {@code addRenderableWidget} method is {@code protected}.
     *
     * <p>The method lives on Minecraft's {@code Screen} base class but is only visible to subclasses.
     * This mod lives in {@code com.example.elink.ui}, so we reach it through reflection instead of
     * spreading access-modifier workarounds across the package tree.
     */
    private static void addWidgetReflectively(final net.minecraft.client.gui.screens.Screen screen, final Button btn) {
        try {
            java.lang.reflect.Method addWidget = null;
            Class<?> hierarchy = screen.getClass();
            while (hierarchy != null && addWidget == null) {
                try {
                    addWidget = hierarchy.getDeclaredMethod("addRenderableWidget",
                            net.minecraft.client.gui.components.events.GuiEventListener.class);
                } catch (NoSuchMethodException ignored) {
                    hierarchy = hierarchy.getSuperclass();
                }
            }
            if (addWidget == null) {
                throw new IllegalStateException("Screen.addRenderableWidget not found");
            }
            addWidget.setAccessible(true);
            addWidget.invoke(screen, btn);
        } catch (ReflectiveOperationException e) {
            LOGGER.warn("Could not add an ELink widget to {} on the title screen", screen, e);
        }
    }

    /*? if fabric {*/
    /**
     * Fabric implementation. {@code ScreenEvents.AFTER_INIT} fires after Minecraft has wired its own
     * widgets, so we can safely append ours.
     *
     * <p>This implementation uses reflection so the file compiles even on Forge / NeoForge nodes
     * where {@code fabric-screen-api-v1} is not on the classpath — Stonecutter's compiler only
     * includes the Fabric body for Fabric nodes, but at edit time we want a single source file.
     */
    private static void registerFabric() {
        try {
            Class<?> screenEventsCls = Class.forName("net.fabricmc.fabric.api.client.screen.v1.ScreenEvents");
            Class<?> afterInitCls = Class.forName("net.fabricmc.fabric.api.client.screen.v1.ScreenEvents$AfterInit");
            Object afterInitEvent = screenEventsCls.getField("AFTER_INIT").get(null);

            // Build a dynamic proxy for the AfterInit interface; Fabric's event bus invokes
            // afterInit(Minecraft, Screen, int, int) and we capture the screen argument from there.
            // Type-erasure makes the lookup uniform across the 1.20.x / 1.21.x API shapes.
            Object proxy = java.lang.reflect.Proxy.newProxyInstance(
                    TitleOverlay.class.getClassLoader(),
                    new Class<?>[] {afterInitCls},
                    (proxyObj, method, args) -> {
                        if (method.getName().equals("afterInit") && args != null && args.length == 4) {
                            handleScreenOpened(args[1]);
                        }
                        return null;
                    });

            java.lang.reflect.Method registerMethod =
                    afterInitEvent.getClass().getMethod("register", java.util.function.Consumer.class);
            // The Consumer type parameter erased to Consumer<Object>; the proxy instance is the
            // AfterInit the event bus will hand to the consumer.
            registerMethod.invoke(afterInitEvent, proxy);
            LOGGER.info("Registered the ELink title-screen overlay via Fabric ScreenEvents");
        } catch (Throwable t) {
            LOGGER.warn("Fabric ScreenEvents not available, the title-screen overlay is disabled", t);
        }
    }
    /*?}*/

    /**
     * Shared body invoked by every loader's screen-initialised hook. Defers the actual
     * button-add to {@link #onTitleScreenReady(Screen)} so the Forge and NeoForge branches can
     * share the same code path as Fabric.
     *
     * <p>Lives outside any Stonecutter branch because Forge and NeoForge both call it too — only
     * the way they obtain a {@link TitleScreen} differs.
     *
     * @return the placeholder widget so Forge / NeoForge's {@code ScreenEvent.Init.addListener}
     *         keeps the bus's own bookkeeping happy. The widget itself is never rendered.
     */
    private static net.minecraft.client.gui.components.events.GuiEventListener handleScreenOpened(final Object screenObj) {
        if (!(screenObj instanceof TitleScreen)) {
            return DUMMY_WIDGET;
        }
        final TitleScreen screen = (TitleScreen) screenObj;
        // Defer to the client thread: the event fires while Minecraft is still wiring its own
        // widgets, and we don't want to interleave with that.
        net.minecraft.client.Minecraft.getInstance().execute(() -> onTitleScreenReady(screen));
        return DUMMY_WIDGET;
    }

    /**
     * An invisible placeholder widget. Forge / NeoForge's {@code ScreenEvent.Init.addListener}
     * expects a {@code GuiEventListener} back; we hand it this object so the bus is happy, then
     * the actual button is added via {@link #addWidgetReflectively} once the client thread picks
     * up the deferred task.
     *
     * <p>It implements every interface {@code Screen#addRenderableWidget} accepts so it stays
     * type-compatible regardless of which bus handed us the event.
     */
    private static final net.minecraft.client.gui.components.events.GuiEventListener DUMMY_WIDGET =
            (net.minecraft.client.gui.components.events.GuiEventListener) createDummyWidget();

    /**
     * Build a no-op widget that satisfies every interface constraint {@code Screen#addRenderableWidget}
     * declares on its generic type parameter. We use a dynamic proxy because anonymous classes
     * cannot declare more than one interface implementation in Java source.
     */
    private static Object createDummyWidget() {
        final Class<?>[] interfaces = new Class<?>[] {
                net.minecraft.client.gui.components.Renderable.class,
                net.minecraft.client.gui.components.events.GuiEventListener.class,
                net.minecraft.client.gui.narration.NarratableEntry.class
        };
        return java.lang.reflect.Proxy.newProxyInstance(
                TitleOverlay.class.getClassLoader(),
                interfaces,
                (proxyObj, method, args) -> {
                    final Class<?> r = method.getReturnType();
                    if (r == boolean.class) {
                        return Boolean.FALSE;
                    }
                    if (r == int.class) {
                        return 0;
                    }
                    if (r == double.class) {
                        return 0.0d;
                    }
                    if (r == float.class) {
                        return 0.0f;
                    }
                    if (r == void.class) {
                        return null;
                    }
                    return null;
                });
    }

    private static void onTitleScreenReady(final net.minecraft.client.gui.screens.Screen screen) {
        if (!(screen instanceof TitleScreen)) {
            return;
        }
        final int x = ((TitleScreen) screen).width / 2 - BUTTON_W / 2;
        final int y = ((TitleScreen) screen).height / 4 + 48 + 24 + 24;
        final Button btn = buildButton(x, y);
        addWidgetReflectively(screen, btn);
    }

    /*? if neoforge {*/
    /*
    private static void registerNeoForge() {
        // NeoForge 1.21.1 exposes ScreenEvent.Init.Post on the game event bus; addListener appends
        // the widget through the supported path. addRenderableWidget is protected on Screen, so we
        // still go through the reflective helper defined above.
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.client.event.ScreenEvent.Init.Post event) -> {
                    event.addListener(handleScreenOpened(event.getScreen()));
                });
        LOGGER.info("Registered the ELink title-screen overlay via NeoForge ScreenEvent.Init.Post");
    }
    *//*?}*/

    /*? if forge {*/
    /*
    private static void registerForge() {
        // Forge 1.20.1+ exposes ScreenEvent.Init.Post on the game event bus. addListener gives the
        // event-bus side of the registration; addRenderableWidget is protected on Screen, so we
        // still go through the reflective helper.
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener(
                (net.minecraftforge.client.event.ScreenEvent.Init.Post event) -> {
                    event.addListener(handleScreenOpened(event.getScreen()));
                });
        LOGGER.info("Registered the ELink title-screen overlay via Forge ScreenEvent.Init.Post");
    }
    *//*?}*/
}
