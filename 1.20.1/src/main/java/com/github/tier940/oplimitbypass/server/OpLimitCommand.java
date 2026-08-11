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

    private static final SuggestionProvider<CommandSourceStack> MAINTENANCE_NAMES =
            (context, builder) -> SharedSuggestionProvider.suggest(OpBypassRegistry.listMaintenanceNames(), builder);

    private OpLimitCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("oplimit")
                .requires(source -> source.hasPermission(4))
                // Bare /oplimit reports status instead of a usage error.
                .executes(OpLimitCommand::status)
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
                        // 0 is allowed: it is how you close the server to everyone but the
                        // operators who bypass the limit.
                        .then(Commands.argument("value", IntegerArgumentType.integer(0, MAX_PLAYER_CAP))
                                .executes(OpLimitCommand::setMax)))
                .then(Commands.literal("maintenance")
                        .executes(OpLimitCommand::queryMaintenance)
                        .then(Commands.literal("on").executes(OpLimitCommand::maintenanceOn))
                        .then(Commands.literal("off").executes(OpLimitCommand::maintenanceOff))
                        .then(Commands.literal("list").executes(OpLimitCommand::maintenanceList))
                        .then(Commands.literal("add")
                                .then(Commands.argument("player", StringArgumentType.word())
                                        .executes(OpLimitCommand::maintenanceAdd)))
                        .then(Commands.literal("remove")
                                .then(Commands.argument("player", StringArgumentType.word())
                                        .suggests(MAINTENANCE_NAMES)
                                        .executes(OpLimitCommand::maintenanceRemove)))));
    }

    private static int status(CommandContext<CommandSourceStack> context) {
        PlayerList playerList = context.getSource().getServer().getPlayerList();
        List<ServerPlayer> players = playerList.getPlayers();
        int max = playerList.getMaxPlayers();
        int bypassing = OpBypassRegistry.countBypassingOnline(players);
        int counted = OpBypassRegistry.countNonBypassing(players);
        info(context, "oplimit.status", max, players.size(), counted, bypassing, Math.max(0, max - counted));
        if (OpBypassRegistry.isMaintenance()) {
            return info(context, "oplimit.status.maintenance", OpBypassRegistry.getSavedMaxPlayers());
        }
        return 1;
    }

    private static int reload(CommandContext<CommandSourceStack> context) {
        int count = OpBypassRegistry.reload();
        OpBypassRegistry.reloadMaintenance();
        OpBypassCounter.reloadVanillaOps(context.getSource().getServer());
        return info(context, "oplimit.reload", count);
    }

    private static int list(CommandContext<CommandSourceStack> context) {
        List<String> names = OpBypassRegistry.listBypassing();
        if (names.isEmpty()) {
            return info(context, "oplimit.list.empty");
        }
        return info(context, "oplimit.list", names.size(), String.join(", ", names));
    }

    private static int queryBypass(CommandContext<CommandSourceStack> context) {
        String name = StringArgumentType.getString(context, "player");
        Boolean state = OpBypassRegistry.getState(name);
        if (state == null) {
            return info(context, "oplimit.bypass.unset", name);
        }
        return info(context, "oplimit.bypass.state", name, state);
    }

    private static int setBypass(CommandContext<CommandSourceStack> context) {
        String name = StringArgumentType.getString(context, "player");
        boolean value = BoolArgumentType.getBool(context, "value");
        MinecraftServer server = context.getSource().getServer();

        switch (OpBypassRegistry.setBypass(name, value)) {
            case NOT_OP:
                return error(context, "oplimit.bypass.not_op", name, name);
            case IO_ERROR:
                return error(context, "oplimit.bypass.io_error");
            case OK:
            default:
                OpBypassCounter.reloadVanillaOps(server);
                return info(context, "oplimit.bypass.set", value, name);
        }
    }

    private static int queryMax(CommandContext<CommandSourceStack> context) {
        return info(context, "oplimit.max.current",
                context.getSource().getServer().getPlayerList().getMaxPlayers());
    }

    private static int setMax(CommandContext<CommandSourceStack> context) {
        int value = IntegerArgumentType.getInteger(context, "value");
        PlayerList playerList = context.getSource().getServer().getPlayerList();
        int current = playerList.getMaxPlayers();
        OpBypassCounter.setMaxPlayers(playerList, value);
        info(context, "oplimit.max.changed", current, playerList.getMaxPlayers());
        return info(context, "oplimit.max.not_persisted");
    }

    // --------------------------------------------------------------------------------- maintenance

    private static int queryMaintenance(CommandContext<CommandSourceStack> context) {
        if (!OpBypassRegistry.isMaintenance()) {
            return info(context, "oplimit.maintenance.off");
        }
        return info(context, "oplimit.maintenance.on_status", OpBypassRegistry.getSavedMaxPlayers());
    }

    private static int maintenanceOn(CommandContext<CommandSourceStack> context) {
        if (OpBypassRegistry.isMaintenance()) {
            return error(context, "oplimit.maintenance.already_on");
        }
        MinecraftServer server = context.getSource().getServer();
        PlayerList playerList = server.getPlayerList();
        OpBypassRegistry.beginMaintenance(playerList.getMaxPlayers(), OpBypassCounter.getMotd(server));
        OpBypassCounter.setMaxPlayers(playerList, 0);
        OpBypassCounter.setMotd(server, OpLimitLang.translate("oplimit.motd.maintenance"));
        int kicked = OpBypassCounter.kickDisallowed(playerList, OpLimitLang.translate("oplimit.disconnect.maintenance"));
        info(context, "oplimit.maintenance.enabled", kicked);
        return info(context, "oplimit.maintenance.enabled.hint");
    }

    private static int maintenanceOff(CommandContext<CommandSourceStack> context) {
        int restore = OpBypassRegistry.endMaintenance();
        if (restore < 0) {
            return error(context, "oplimit.maintenance.not_on");
        }
        MinecraftServer server = context.getSource().getServer();
        PlayerList playerList = server.getPlayerList();
        OpBypassCounter.setMaxPlayers(playerList, restore);
        String motd = OpBypassRegistry.takeSavedMotd();
        if (motd != null) {
            OpBypassCounter.setMotd(server, motd);
        }
        return info(context, "oplimit.maintenance.disabled", restore);
    }

    private static int maintenanceList(CommandContext<CommandSourceStack> context) {
        List<String> names = OpBypassRegistry.listMaintenanceNames();
        if (names.isEmpty()) {
            return info(context, "oplimit.maintenance.list.empty");
        }
        return info(context, "oplimit.maintenance.list", names.size(), String.join(", ", names));
    }

    private static int maintenanceAdd(CommandContext<CommandSourceStack> context) {
        String name = StringArgumentType.getString(context, "player");
        MinecraftServer server = context.getSource().getServer();
        if (!OpBypassRegistry.addMaintenanceName(name, OpBypassCounter.uuidOf(server, name))) {
            return error(context, "oplimit.maintenance.already_listed", name);
        }
        return info(context, "oplimit.maintenance.added", name);
    }

    private static int maintenanceRemove(CommandContext<CommandSourceStack> context) {
        String name = StringArgumentType.getString(context, "player");
        if (!OpBypassRegistry.removeMaintenanceName(name)) {
            return error(context, "oplimit.maintenance.not_listed", name);
        }
        info(context, "oplimit.maintenance.removed", name);
        if (!OpBypassRegistry.isMaintenance()) {
            return 1;
        }
        // They are no longer allowed in, so evict them the same way enabling maintenance does.
        // A bypassing operator stays: kickDisallowed is the single definition of "may be here".
        PlayerList playerList = context.getSource().getServer().getPlayerList();
        int kicked = OpBypassCounter.kickDisallowed(playerList, OpLimitLang.translate("oplimit.disconnect.maintenance"));
        if (kicked > 0) {
            return info(context, "oplimit.maintenance.kicked", kicked);
        }
        return 1;
    }

    /**
     * Sends a message as a translation key so a client that has this mod renders it in its own
     * language, with the server-resolved text riding along as the fallback for the clients that do
     * not (which is most of them: this mod is server-only).
     */
    private static Component message(String key, Object... args) {
        return Component.literal("[OpLimit] ")
                .append(Component.translatableWithFallback(key, OpLimitLang.translate(key, args), args));
    }

    private static int info(CommandContext<CommandSourceStack> context, String key, Object... args) {
        Component text = message(key, args);
        context.getSource().sendSuccess(() -> text, true);
        return 1;
    }

    private static int error(CommandContext<CommandSourceStack> context, String key, Object... args) {
        context.getSource().sendFailure(message(key, args));
        return 0;
    }
}
