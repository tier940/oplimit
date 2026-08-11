package com.github.tier940.oplimitbypass.server;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;

import com.github.tier940.oplimitbypass.mixins.minecraft.AccessorPlayerList;

/**
 * The 1.20.1 half of the registry: the few operations that have to name a Minecraft type.
 *
 * <p>
 * {@link OpBypassRegistry} is shared verbatim with the 1.12.2 build, so it cannot reference
 * {@code ServerPlayer} or {@code PlayerList}. It only ever needs one thing from a Minecraft version
 * — how to get a profile out of a player — which {@link #PROFILE_READER} supplies once at startup.
 * The two methods below are called from the command class and never from shared code.
 */
public final class OpBypassCounter {

    public static final OpBypassRegistry.ProfileReader PROFILE_READER = player -> player instanceof ServerPlayer serverPlayer
            ? serverPlayer.getGameProfile()
            : null;

    private OpBypassCounter() {}

    /** Pushes the on-disk operator list back into the running server. Best effort. */
    public static void reloadVanillaOps(MinecraftServer server) {
        try {
            server.getPlayerList().getOps().load();
        } catch (Exception e) {
            OpBypassRegistry.LOGGER.warn("Could not refresh the vanilla operator list", e);
        }
    }

    /** Changes the player cap in memory. Not persisted, server.properties still wins on restart. */
    public static void setMaxPlayers(PlayerList playerList, int maxPlayers) {
        ((AccessorPlayerList) playerList).oplimit$setMaxPlayers(maxPlayers);
    }
}
