package com.example.mcp2p.command;

import com.example.mcp2p.McP2p;
import com.example.mcp2p.config.McP2pConfig;
import com.example.mcp2p.identity.PlayerIdentity;
import com.example.mcp2p.platform.Platform;
import com.example.mcp2p.signalling.RoomSecret;
import com.example.mcp2p.tunnel.TunnelSession;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import java.util.Optional;
import java.util.Set;
import net.minecraft.network.chat.Component;

/**
 * The {@code /mcp2p} command: share the local world, join a friend's room and inspect the tunnel.
 *
 * <p>Only the tunnel is started here; the MQTT and WebRTC work happens on background threads, so the
 * command returns immediately and reports progress in the log.
 */
public final class McP2pCommand {
    private McP2pCommand() {}

    /** Registers the command tree; called from the common entrypoint. */
    public static void register() {
        Platform.registerCommands(McP2pCommand::build);
    }

    private static void build(final CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal("mcp2p")
                        .executes(context -> status(context.getSource()))
                        .then(Commands.literal("host").executes(context -> host(context.getSource())))
                        .then(
                                Commands.literal("join")
                                        .then(
                                                Commands.argument("room", StringArgumentType.word())
                                                        .executes(
                                                                context ->
                                                                        join(
                                                                                context.getSource(),
                                                                                StringArgumentType.getString(
                                                                                        context, "room"),
                                                                                null))
                                                        .then(
                                                                Commands.argument(
                                                                                "key",
                                                                                StringArgumentType.word())
                                                                        .executes(
                                                                                context ->
                                                                                        join(
                                                                                                context.getSource(),
                                                                                                StringArgumentType.getString(
                                                                                                        context, "room"),
                                                                                                StringArgumentType.getString(
                                                                                                        context, "key"))))))
                        .then(
                                Commands.literal("leave")
                                        .executes(context -> leave(context.getSource())))
                        .then(
                                Commands.literal("status")
                                        .executes(context -> status(context.getSource())))
                        .then(
                                Commands.literal("whoami")
                                        .executes(context -> whoami(context.getSource())))
                        .then(
                                Commands.literal("login")
                                        .then(
                                                Commands.argument("name", StringArgumentType.word())
                                                        .then(
                                                                Commands.argument(
                                                                                "password",
                                                                                StringArgumentType.string())
                                                                        .executes(
                                                                                context ->
                                                                                        login(
                                                                                                context.getSource(),
                                                                                                StringArgumentType.getString(
                                                                                                        context, "name"),
                                                                                                StringArgumentType.getString(
                                                                                                        context, "password"))))))
                        .then(
                                Commands.literal("friends")
                                        .executes(context -> friends(context.getSource(), null))
                                        .then(
                                                Commands.argument(
                                                                "list", StringArgumentType.greedyString())
                                                        .executes(
                                                                context ->
                                                                        friends(
                                                                                context.getSource(),
                                                                                StringArgumentType.getString(
                                                                                        context, "list"))))));
    }

    private static int host(final CommandSourceStack source) {
        final McP2pConfig config = McP2p.config();
        if (config.getRoomSecret().isBlank()) {
            // First time hosting: protect the room, so knowing the room code is not enough.
            config.setRoomSecret(RoomSecret.generate());
            config.save(McP2p.configDir());
        }

        if (!McP2p.ensureInRoom(config.getRoomCode())) {
            reply(source, "Could not reach the signalling broker, see the log for details");
            return 0;
        }

        reply(source, McP2p.startHosting());
        reply(
                source,
                "Friends join with /mcp2p join "
                        + config.getRoomCode()
                        + ' '
                        + config.getRoomSecret());
        return Command.SINGLE_SUCCESS;
    }

    private static int join(final CommandSourceStack source, final String room, final String key) {
        final McP2pConfig config = McP2p.config();
        config.setRoomCode(room);
        if (key != null) {
            config.setRoomSecret(key);
        }
        config.save(McP2p.configDir());

        if (!McP2p.ensureInRoom(room)) {
            reply(source, "Could not reach the signalling broker, see the log for details");
            return 0;
        }

        McP2p.tunnel().stop();
        reply(source, McP2p.startJoining());
        return Command.SINGLE_SUCCESS;
    }

    private static int leave(final CommandSourceStack source) {
        McP2p.tunnel().stop();
        McP2p.leaveRoom();
        reply(source, "Left the room and closed the tunnel");
        return Command.SINGLE_SUCCESS;
    }

    private static int status(final CommandSourceStack source) {
        final McP2pConfig config = McP2p.config();
        final TunnelSession tunnel = McP2p.tunnel();

        reply(source, "Room " + config.getRoomCode() + " on " + config.getBrokerUri());
        reply(
                source,
                config.getRoomSecret().isBlank()
                        ? "Open room: knowing the room code is enough to join"
                        : "Room key is set, peers have to prove that they know it");
        reply(
                source,
                McP2p.identity()
                        .identity()
                        .map(
                                account ->
                                        "Signed in as "
                                                + account.name()
                                                + " ("
                                                + account.dashedUuid()
                                                + ")")
                        .orElse(
                                "No account signed in; /mcp2p login <email> <password> signs one in"));
        if (!config.friendList().isEmpty()) {
            reply(
                    source,
                    "Only these accounts may connect: " + String.join(", ", config.friendList()));
        }

        if (tunnel.isHosting()) {
            reply(source, "Hosting, incoming connections go to 127.0.0.1:" + tunnel.targetPort());
        } else if (tunnel.isActive()) {
            reply(source, "Joining, connect to 127.0.0.1:" + tunnel.localPort());
        } else {
            reply(source, "Tunnel idle. Use /mcp2p host, or /mcp2p join <room>");
        }

        if (tunnel.isActive()) {
            reply(
                    source,
                    "Peers " + tunnel.peerCount() + ", tunnelled connections " + tunnel.streamCount());
        }
        return Command.SINGLE_SUCCESS;
    }

    private static void reply(final CommandSourceStack source, final String text) {
        source.sendSuccess(() -> Component.literal(text), false);
    }

    private static int login(final CommandSourceStack source, final String name, final String password) {
        reply(source, "Talking to the account service, the answer shows up in the chat in a moment");
        McP2p.identity().loginAsync(name, password, message -> replyLater(source, message));
        return Command.SINGLE_SUCCESS;
    }

    private static int whoami(final CommandSourceStack source) {
        final Optional<PlayerIdentity> account = McP2p.identity().identity();
        if (account.isEmpty()) {
            reply(source, "No account signed in, use /mcp2p login <email> <password>");
            return Command.SINGLE_SUCCESS;
        }
        reply(
                source,
                "Signed in as " + account.get().name() + " (" + account.get().dashedUuid() + ")");
        return Command.SINGLE_SUCCESS;
    }

    private static int friends(final CommandSourceStack source, final String list) {
        final McP2pConfig config = McP2p.config();

        if (list == null) {
            final Set<String> current = config.friendList();
            reply(
                    source,
                    current.isEmpty()
                            ? "The friend list is empty: anybody with the room key may connect"
                            : "Only these accounts may connect: " + String.join(", ", current));
            return Command.SINGLE_SUCCESS;
        }

        if (list.equalsIgnoreCase("clear") || list.equalsIgnoreCase("none")) {
            config.setFriends("");
        } else {
            config.setFriends(list);
        }
        config.save(McP2p.configDir());

        reply(
                source,
                config.friendList().isEmpty()
                        ? "The friend list is empty again: anybody with the room key may connect"
                        : "Only these accounts may connect now: "
                                + String.join(", ", config.friendList()));
        return Command.SINGLE_SUCCESS;
    }

    /** Sends a message that arrived on a background thread from the server thread. */
    private static void replyLater(final CommandSourceStack source, final String text) {
        if (text == null) {
            return;
        }
        source.getServer().execute(() -> reply(source, text));
    }
}
