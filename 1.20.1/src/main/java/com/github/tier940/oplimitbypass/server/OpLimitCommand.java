package com.github.tier940.oplimitbypass.server;

import java.util.List;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;

/**
 * Administrative command for the operator player-limit bypass. Permission level 4.
 */
public final class OpLimitCommand {

    private static final int MAX_PLAYER_CAP = 100000;

    private static final SuggestionProvider<CommandSourceStack> OPERATORS =
            (context, builder) -> SharedSuggestionProvider.suggest(OpBypassRegistry.listOperators(), builder);

    private OpLimitCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("oplimit")
                .requires(source -> source.hasPermission(4))
                .then(Commands.literal("status").executes(OpLimitCommand::status))
                .then(Commands.literal("reload").executes(OpLimitCommand::reload))
                .then(Commands.literal("list").executes(OpLimitCommand::list))
                .then(Commands.literal("bypass")
                        .then(Commands.argument("player", StringArgumentType.word())
                                .suggests(OPERATORS)
                                .executes(OpLimitCommand::queryBypass)
                                .then(Commands.argument("value", BoolArgumentType.bool())
                                        .executes(OpLimitCommand::setBypass))))
                .then(Commands.literal("max")
                        .executes(OpLimitCommand::queryMax)
                        .then(Commands.argument("value", IntegerArgumentType.integer(1, MAX_PLAYER_CAP))
                                .executes(OpLimitCommand::setMax))));

        dispatcher.register(Commands.literal("oplb")
                .requires(source -> source.hasPermission(4))
                .redirect(dispatcher.getRoot().getChild("oplimit")));
    }

    private static int status(CommandContext<CommandSourceStack> context) {
        PlayerList playerList = context.getSource().getServer().getPlayerList();
        List<ServerPlayer> players = playerList.getPlayers();
        int max = playerList.getMaxPlayers();
        int bypassing = OpBypassRegistry.countBypassingOnline(players);
        int counted = OpBypassRegistry.countNonBypassing(players);
        return info(context, String.format("max-players %d, online %d (%d counted + %d bypassing), %d slot(s) free",
                max, players.size(), counted, bypassing, Math.max(0, max - counted)));
    }

    private static int reload(CommandContext<CommandSourceStack> context) {
        int count = OpBypassRegistry.reload();
        OpBypassCounter.reloadVanillaOps(context.getSource().getServer());
        return info(context, "Reloaded ops.json, " + count + " operator(s) bypass the player limit.");
    }

    private static int list(CommandContext<CommandSourceStack> context) {
        List<String> names = OpBypassRegistry.listBypassing();
        if (names.isEmpty()) {
            return info(context, "No operator currently bypasses the player limit.");
        }
        return info(context, "Bypassing the player limit (" + names.size() + "): " + String.join(", ", names));
    }

    private static int queryBypass(CommandContext<CommandSourceStack> context) {
        String name = StringArgumentType.getString(context, "player");
        Boolean state = OpBypassRegistry.getState(name);
        if (state == null) {
            return info(context, name + " has no ops.json entry, so the flag is unset (false).");
        }
        return info(context, name + ": bypassesPlayerLimit = " + state);
    }

    private static int setBypass(CommandContext<CommandSourceStack> context) {
        String name = StringArgumentType.getString(context, "player");
        boolean value = BoolArgumentType.getBool(context, "value");
        MinecraftServer server = context.getSource().getServer();

        switch (OpBypassRegistry.setBypass(name, value)) {
            case NOT_OP:
                return error(context, name + " is not in ops.json. Run /op " + name + " first.");
            case IO_ERROR:
                return error(context, "Could not update ops.json, see the server log.");
            case OK:
            default:
                OpBypassCounter.reloadVanillaOps(server);
                return info(context, "Set bypassesPlayerLimit = " + value + " for " + name
                        + ". Applied immediately.");
        }
    }

    private static int queryMax(CommandContext<CommandSourceStack> context) {
        return info(context, "max-players is currently "
                + context.getSource().getServer().getPlayerList().getMaxPlayers() + ".");
    }

    private static int setMax(CommandContext<CommandSourceStack> context) {
        int value = IntegerArgumentType.getInteger(context, "value");
        PlayerList playerList = context.getSource().getServer().getPlayerList();
        int current = playerList.getMaxPlayers();
        OpBypassCounter.setMaxPlayers(playerList, value);
        info(context, "max-players changed from " + current + " to " + playerList.getMaxPlayers() + ".");
        return info(context, "This is in-memory only. Update server.properties to make it permanent.");
    }

    private static int info(CommandContext<CommandSourceStack> context, String message) {
        context.getSource().sendSuccess(() -> Component.literal("[OpLimit] " + message), true);
        return 1;
    }

    private static int error(CommandContext<CommandSourceStack> context, String message) {
        context.getSource().sendFailure(Component.literal("[OpLimit] " + message));
        return 0;
    }
}
