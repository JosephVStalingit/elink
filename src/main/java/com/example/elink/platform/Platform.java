package com.example.elink.platform;

import com.mojang.brigadier.CommandDispatcher;
import java.nio.file.Path;
import java.util.function.Consumer;
import net.minecraft.commands.CommandSourceStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/*? if fabric {*/
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.loader.api.FabricLoader;
/*?}*/

/*? if forge {*/
/*import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.loading.FMLPaths;
*//*?}*/

/*? if neoforge {*/
/*import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
*//*?}*/

/**
 * The few things that differ between the loaders: where the game keeps its configuration, how the mod
 * version is read, whether this process has a game window, and when commands may be registered.
 * Everything else in 易联 is plain Java and runs unchanged on all of them.
 *
 * <p>The loader specific code sits in Stonecutter branches, so exactly one variant is compiled into
 * each build.
 */
public final class Platform {
    private static final Logger LOGGER = LoggerFactory.getLogger("elink/platform");

    private Platform() {}

    /** @return the directory the game keeps its configuration in */
    public static Path configDir() {
        /*? if fabric {*/
        return FabricLoader.getInstance().getConfigDir();
        /*?}*/
        /*? if forge {*/
        /*return FMLPaths.CONFIGDIR.get();
        *//*?}*/
        /*? if neoforge {*/
        /*return FMLPaths.CONFIGDIR.get();
        *//*?}*/
    }

    /** @return {@code true} when this process has a game window */
    public static boolean isClient() {
        /*? if fabric {*/
        return FabricLoader.getInstance().getEnvironmentType() == net.fabricmc.api.EnvType.CLIENT;
        /*?}*/
        /*? if forge {*/
        /*return FMLEnvironment.dist == net.minecraftforge.api.distmarker.Dist.CLIENT;
        *//*?}*/
        /*? if neoforge {*/
        /*return FMLEnvironment.dist == net.neoforged.api.distmarker.Dist.CLIENT;
        *//*?}*/
    }

    /**
     * @param modId the mod to look up
     * @return the version of that mod as the loader knows it
     */
    public static String modVersion(final String modId) {
        /*? if fabric {*/
        return FabricLoader.getInstance()
                .getModContainer(modId)
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
        /*?}*/
        /*? if forge {*/
        /*return ModList.get().getModFileById(modId).versionString();
        *//*?}*/
        /*? if neoforge {*/
        /*return ModList.get().getModFileById(modId).versionString();
        *//*?}*/
    }

    /**
     * Registers the command tree. Every loader hands the dispatcher over at a different moment, so the
     * caller only supplies the builder and this method wires it up.
     */
    public static void registerCommands(
            final Consumer<CommandDispatcher<CommandSourceStack>> builder) {
        /*? if fabric {*/
        CommandRegistrationCallback.EVENT.register(
                (dispatcher, registryAccess, environment) -> builder.accept(dispatcher));
        /*?}*/
        /*? if forge {*/
        /*MinecraftForge.EVENT_BUS.addListener(
                (RegisterCommandsEvent event) -> builder.accept(event.getDispatcher()));
        *//*?}*/
        /*? if neoforge {*/
        /*NeoForge.EVENT_BUS.addListener(
                (RegisterCommandsEvent event) -> builder.accept(event.getDispatcher()));
        *//*?}*/
    }

    /**
     * Opens the client UI. Implemented in the client-only classloader (see {@code ELinkClient}) so
     * the common code does not need a direct dependency on the screen.
     *
     * <p>The handler is a {@link Runnable} because the work has to run on the client thread; the
     * command dispatcher invokes us from the server thread.
     */
    private static volatile Runnable uiOpener = () -> {};

    /** Called by the client-only bootstrap to plug in the real implementation. */
    public static void setUiOpener(final Runnable opener) {
        uiOpener = opener == null ? () -> {} : opener;
    }

    /** Asks the client to open the UI. No-op on a dedicated server. */
    public static void openUi() {
        uiOpener.run();
    }

    /** Logs which platform the mod is running on, for support requests. */
    public static void logDetails() {
        LOGGER.info("Running on {} with the mod at {}", platformName(), Platform.class.getProtectionDomain()
                .getCodeSource() == null ? "unknown" : "a mod jar");
    }

    /**
     * @return the human-readable loader name. Public so the UI can show it without duplicating the
     *         Stonecutter branches.
     */
    public static String platformName() {
        /*? if fabric {*/
        return "Fabric";
        /*?}*/
        /*? if forge {*/
        /*return "Forge";
        *//*?}*/
        /*? if neoforge {*/
        /*return "NeoForge";
        *//*?}*/
    }
}
