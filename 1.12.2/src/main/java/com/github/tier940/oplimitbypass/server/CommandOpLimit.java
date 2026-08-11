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

    private static final String USAGE = "/oplimit <status|reload|list|bypass|max>";
    private static final int MAX_PLAYER_CAP = 100000;

    @Override
    @NotNull
    public String getName() {
        return "oplimit";
    }

    @Override
    @NotNull
    public List<String> getAliases() {
        return Collections.singletonList("oplb");
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
        info(sender, String.format("max-players %d, online %d (%d counted + %d bypassing), %d slot(s) free",
                max, players.size(), counted, bypassing, Math.max(0, max - counted)));
    }

    private void reload(MinecraftServer server, ICommandSender sender) {
        int count = OpBypassRegistry.reload();
        OpBypassCounter.reloadVanillaOps(server);
        info(sender, "Reloaded ops.json, " + count + " operator(s) bypass the player limit.");
    }

    private void list(ICommandSender sender) {
        List<String> names = OpBypassRegistry.listBypassing();
        if (names.isEmpty()) {
            info(sender, "No operator currently bypasses the player limit.");
        } else {
            info(sender, "Bypassing the player limit (" + names.size() + "): " + String.join(", ", names));
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
                info(sender, name + " has no ops.json entry, so the flag is unset (false).");
            } else {
                info(sender, name + ": bypassesPlayerLimit = " + state);
            }
            return;
        }

        boolean value = parseFlag(args[2]);
        switch (OpBypassRegistry.setBypass(name, value)) {
            case NOT_OP:
                error(sender, name + " is not in ops.json. Run /op " + name + " first.");
                return;
            case IO_ERROR:
                error(sender, "Could not update ops.json, see the server log.");
                return;
            case OK:
            default:
                OpBypassCounter.reloadVanillaOps(server);
                info(sender, "Set bypassesPlayerLimit = " + value + " for " + name + ". Applied immediately.");
        }
    }

    private void max(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        PlayerList playerList = server.getPlayerList();
        int current = playerList.getMaxPlayers();

        if (args.length < 2) {
            info(sender, "max-players is currently " + current + ".");
            return;
        }

        int value = parseInt(args[1], 1, MAX_PLAYER_CAP);
        OpBypassCounter.setMaxPlayers(playerList, value);
        info(sender, "max-players changed from " + current + " to " + playerList.getMaxPlayers() + ".");
        info(sender, "This is in-memory only. Update server.properties to make it permanent.");
    }

    @Override
    @NotNull
    public List<String> getTabCompletions(@NotNull MinecraftServer server, @NotNull ICommandSender sender,
                                          @NotNull String[] args, @Nullable BlockPos targetPos) {
        if (args.length == 1) {
            return getListOfStringsMatchingLastWord(args, "status", "reload", "list", "bypass", "max");
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

    private static void info(ICommandSender sender, String message) {
        sender.sendMessage(new TextComponentString(TextFormatting.GRAY + "[OpLimit] " + message));
    }

    private static void error(ICommandSender sender, String message) {
        sender.sendMessage(new TextComponentString(TextFormatting.RED + "[OpLimit] " + message));
    }
}
