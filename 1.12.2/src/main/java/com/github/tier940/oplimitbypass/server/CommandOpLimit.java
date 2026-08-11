package com.github.tier940.oplimitbypass.server;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.management.PlayerList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Administrative command for the operator player-limit bypass.
 *
 * <pre>
 * /oplimit status
 * /oplimit reload
 * /oplimit list
 * /oplimit bypass &lt;player&gt; [true|false]
 * /oplimit max [amount]
 * </pre>
 */
public class CommandOpLimit extends CommandBase {

    private static final String USAGE = "/oplimit <status|reload|list|bypass|max|maintenance>";
    private static final int MAX_PLAYER_CAP = 100000;

    @Override
    @NotNull
    public String getName() {
        return "oplimit";
    }

    @Override
    @NotNull
    public String getUsage(@NotNull ICommandSender sender) {
        return USAGE;
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 4;
    }

    @Override
    public void execute(@NotNull MinecraftServer server, @NotNull ICommandSender sender,
                        @NotNull String[] args) throws CommandException {
        if (args.length == 0) {
            throw new WrongUsageException(USAGE);
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "status":
                status(server, sender);
                return;
            case "reload":
                reload(server, sender);
                return;
            case "list":
                list(sender);
                return;
            case "bypass":
                bypass(server, sender, args);
                return;
            case "max":
                max(server, sender, args);
                return;
            case "maintenance":
                maintenance(server, sender, args);
                return;
            default:
                throw new WrongUsageException(USAGE);
        }
    }

    private void status(MinecraftServer server, ICommandSender sender) {
        PlayerList playerList = server.getPlayerList();
        List<EntityPlayerMP> players = playerList.getPlayers();
        int max = playerList.getMaxPlayers();
        int bypassing = OpBypassRegistry.countBypassingOnline(players);
        int counted = OpBypassRegistry.countNonBypassing(players);
        info(sender, "oplimit.status", max, players.size(), counted, bypassing, Math.max(0, max - counted));
        if (OpBypassRegistry.isMaintenance()) {
            info(sender, "oplimit.status.maintenance", OpBypassRegistry.getSavedMaxPlayers());
        }
    }

    private void reload(MinecraftServer server, ICommandSender sender) {
        int count = OpBypassRegistry.reload();
        OpBypassRegistry.reloadMaintenance();
        OpBypassCounter.reloadVanillaOps(server);
        info(sender, "oplimit.reload", count);
    }

    private void list(ICommandSender sender) {
        List<String> names = OpBypassRegistry.listBypassing();
        if (names.isEmpty()) {
            info(sender, "oplimit.list.empty");
        } else {
            info(sender, "oplimit.list", names.size(), String.join(", ", names));
        }
    }

    private void bypass(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (args.length < 2) {
            throw new WrongUsageException("/oplimit bypass <player> [true|false]");
        }
        String name = args[1];

        if (args.length == 2) {
            Boolean state = OpBypassRegistry.getState(name);
            if (state == null) {
                info(sender, "oplimit.bypass.unset", name);
            } else {
                info(sender, "oplimit.bypass.state", name, state);
            }
            return;
        }

        boolean value = parseFlag(args[2]);
        switch (OpBypassRegistry.setBypass(name, value)) {
            case NOT_OP:
                error(sender, "oplimit.bypass.not_op", name, name);
                return;
            case IO_ERROR:
                error(sender, "oplimit.bypass.io_error");
                return;
            case OK:
            default:
                OpBypassCounter.reloadVanillaOps(server);
                info(sender, "oplimit.bypass.set", value, name);
        }
    }

    private void max(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        PlayerList playerList = server.getPlayerList();
        int current = playerList.getMaxPlayers();

        if (args.length < 2) {
            info(sender, "oplimit.max.current", current);
            return;
        }

        // 0 is allowed: it is how you close the server to everyone but the operators who bypass.
        int value = parseInt(args[1], 0, MAX_PLAYER_CAP);
        OpBypassCounter.setMaxPlayers(playerList, value);
        info(sender, "oplimit.max.changed", current, playerList.getMaxPlayers());
        info(sender, "oplimit.max.not_persisted");
    }

    private void maintenance(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        PlayerList playerList = server.getPlayerList();
        String action = args.length < 2 ? "" : args[1].toLowerCase(Locale.ROOT);

        switch (action) {
            case "":
                if (!OpBypassRegistry.isMaintenance()) {
                    info(sender, "oplimit.maintenance.off");
                } else {
                    info(sender, "oplimit.maintenance.on_status", OpBypassRegistry.getSavedMaxPlayers());
                }
                return;
            case "on":
                if (OpBypassRegistry.isMaintenance()) {
                    error(sender, "oplimit.maintenance.already_on");
                    return;
                }
                OpBypassRegistry.beginMaintenance(playerList.getMaxPlayers(), OpBypassCounter.getMotd(server));
                OpBypassCounter.setMaxPlayers(playerList, 0);
                OpBypassCounter.setMotd(server, OpLimitLang.translate("oplimit.motd.maintenance"));
                int kicked = OpBypassCounter.kickDisallowed(playerList, OpLimitLang.translate("oplimit.disconnect.maintenance"));
                info(sender, "oplimit.maintenance.enabled", kicked);
                info(sender, "oplimit.maintenance.enabled.hint");
                return;
            case "off":
                int restore = OpBypassRegistry.endMaintenance();
                if (restore < 0) {
                    error(sender, "oplimit.maintenance.not_on");
                    return;
                }
                OpBypassCounter.setMaxPlayers(playerList, restore);
                String motd = OpBypassRegistry.takeSavedMotd();
                if (motd != null) {
                    OpBypassCounter.setMotd(server, motd);
                }
                info(sender, "oplimit.maintenance.disabled", restore);
                return;
            case "list": {
                List<String> names = OpBypassRegistry.listMaintenanceNames();
                if (names.isEmpty()) {
                    info(sender, "oplimit.maintenance.list.empty");
                } else {
                    info(sender, "oplimit.maintenance.list", names.size(), String.join(", ", names));
                }
                return;
            }
            case "add":
            case "remove": {
                if (args.length < 3) {
                    throw new WrongUsageException("/oplimit maintenance " + action + " <player>");
                }
                String name = args[2];
                boolean changed = "add".equals(action)
                        ? OpBypassRegistry.addMaintenanceName(name, OpBypassCounter.uuidOf(server, name))
                        : OpBypassRegistry.removeMaintenanceName(name);
                if (!changed) {
                    error(sender, "add".equals(action)
                            ? "oplimit.maintenance.already_listed"
                            : "oplimit.maintenance.not_listed", name);
                    return;
                }
                info(sender, "add".equals(action)
                        ? "oplimit.maintenance.added"
                        : "oplimit.maintenance.removed", name);
                if ("remove".equals(action) && OpBypassRegistry.isMaintenance()) {
                    // They are no longer allowed in, so evict them the same way enabling
                    // maintenance does. A bypassing operator stays: kickDisallowed is the single
                    // definition of "may be here".
                    int removed = OpBypassCounter.kickDisallowed(playerList, OpLimitLang.translate("oplimit.disconnect.maintenance"));
                    if (removed > 0) {
                        info(sender, "oplimit.maintenance.kicked", removed);
                    }
                }
                return;
            }
            default:
                throw new WrongUsageException("/oplimit maintenance [on|off|list|add|remove]");
        }
    }

    @Override
    @NotNull
    public List<String> getTabCompletions(@NotNull MinecraftServer server, @NotNull ICommandSender sender,
                                          @NotNull String[] args, @Nullable BlockPos targetPos) {
        if (args.length == 1) {
            return getListOfStringsMatchingLastWord(args, "status", "reload", "list", "bypass", "max", "maintenance");
        }
        if ("maintenance".equalsIgnoreCase(args[0])) {
            if (args.length == 2) {
                return getListOfStringsMatchingLastWord(args, "on", "off", "list", "add", "remove");
            }
            if (args.length == 3 && "remove".equalsIgnoreCase(args[1])) {
                return getListOfStringsMatchingLastWord(args, OpBypassRegistry.listMaintenanceNames());
            }
            return Collections.emptyList();
        }
        if ("bypass".equalsIgnoreCase(args[0])) {
            if (args.length == 2) {
                return getListOfStringsMatchingLastWord(args, OpBypassRegistry.listOperators());
            }
            if (args.length == 3) {
                return getListOfStringsMatchingLastWord(args, "true", "false");
            }
        }
        return Collections.emptyList();
    }

    /** Not named parseBoolean: CommandBase declares a public one, and this is deliberately laxer. */
    private static boolean parseFlag(String raw) {
        return Arrays.asList("true", "1", "on", "yes").contains(raw.toLowerCase(Locale.ROOT));
    }

    /**
     * Sends a message as a translation key so a client that has this mod renders it in its own
     * language. 1.12.2 has no fallback form of {@code TextComponentTranslation}, so the
     * server-resolved text is sent as a sibling that clients without the key still display.
     */
    private static void send(ICommandSender sender, TextFormatting colour, String key, Object... args) {
        TextComponentString line = new TextComponentString(colour + "[OpLimit] " + OpLimitLang.translate(key, args));
        sender.sendMessage(line);
    }

    private static void info(ICommandSender sender, String key, Object... args) {
        send(sender, TextFormatting.GRAY, key, args);
    }

    private static void error(ICommandSender sender, String key, Object... args) {
        send(sender, TextFormatting.RED, key, args);
    }
}
