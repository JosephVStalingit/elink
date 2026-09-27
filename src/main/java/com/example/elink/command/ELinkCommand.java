package com.example.elink.command;

import com.example.elink.ELink;
import com.example.elink.config.ELinkConfig;
import com.example.elink.identity.PlayerIdentity;
import com.example.elink.platform.Platform;
import com.example.elink.signalling.RoomSecret;
import com.example.elink.tunnel.TunnelSession;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import java.util.Optional;
import java.util.Set;
import net.minecraft.network.chat.Component;

/**
 * The {@code /elink} command: share the local world, join a friend's room and inspect the tunnel.
 *
 * <p>Only the tunnel is started here; the MQTT and WebRTC work happens on background threads, so the
 * command returns immediately and reports progress in the log.
 */
public final class ELinkCommand {
    private ELinkCommand() {}

    /** Registers the command tree; called from the common entrypoint. */
    public static void register() {
        Platform.registerCommands(ELinkCommand::build);
    }

    private static void build(final CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal("elink")
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
                                                Commands.argument("name", StringArgumentType.string())
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
        final ELinkConfig config = ELink.config();
        if (config.getRoomSecret().isBlank()) {
            // First time hosting: protect the room, so knowing the room code is not enough.
            config.setRoomSecret(RoomSecret.generate());
            config.save(ELink.configDir());
        }

        if (!ELink.ensureInRoom(config.getRoomCode())) {
            reply(source, "Could not reach the signalling broker, see the log for details");
            return 0;
        }

        reply(source, ELink.startHosting());
        reply(
                source,
                "Friends join with /elink join "
                        + config.getRoomCode()
                        + ' '
                        + config.getRoomSecret());
        return Command.SINGLE_SUCCESS;
    }

    private static int join(final CommandSourceStack source, final String room, final String key) {
        final ELinkConfig config = ELink.config();
        config.setRoomCode(room);
        if (key != null) {
            config.setRoomSecret(key);
        }
        config.save(ELink.configDir());

        if (!ELink.ensureInRoom(room)) {
            reply(source, "Could not reach the signalling broker, see the log for details");
            return 0;
        }

        ELink.tunnel().stop();
        reply(source, ELink.startJoining());
        return Command.SINGLE_SUCCESS;
    }

    private static int leave(final CommandSourceStack source) {
        ELink.tunnel().stop();
        ELink.leaveRoom();
        reply(source, "Left the room and closed the tunnel");
        return Command.SINGLE_SUCCESS;
    }

    private static int status(final CommandSourceStack source) {
        final ELinkConfig config = ELink.config();
        final TunnelSession tunnel = ELink.tunnel();

        reply(source, "Room " + config.getRoomCode() + " on " + config.getBrokerUri());
        reply(
                source,
                config.getRoomSecret().isBlank()
                        ? "Open room: knowing the room code is enough to join"
                        : "Room key is set, peers have to prove that they know it");
        reply(
                source,
                ELink.identity()
                        .identity()
                        .map(
                                account ->
                                        "Signed in as "
                                                + account.name()
                                                + " ("
                                                + account.dashedUuid()
                                                + ")")
                        .orElse(
                                "No account signed in; /elink login <email> <password> signs one in"));
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
            reply(source, "Tunnel idle. Use /elink host, or /elink join <room>");
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
        ELink.identity().loginAsync(name, password, message -> replyLater(source, message));
        return Command.SINGLE_SUCCESS;
    }

    private static int whoami(final CommandSourceStack source) {
        final Optional<PlayerIdentity> account = ELink.identity().identity();
        if (account.isEmpty()) {
            reply(source, "No account signed in, use /elink login <email> <password>");
            return Command.SINGLE_SUCCESS;
        }
        reply(
                source,
                "Signed in as " + account.get().name() + " (" + account.get().dashedUuid() + ")");
        return Command.SINGLE_SUCCESS;
    }

    private static int friends(final CommandSourceStack source, final String list) {
        final ELinkConfig config = ELink.config();

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
        config.save(ELink.configDir());

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
