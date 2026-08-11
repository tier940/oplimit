package com.github.tier940.oplimitbypass.server;

import java.util.ArrayList;
import java.util.Optional;
import java.util.UUID;

import javax.annotation.Nullable;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;

import com.github.tier940.oplimitbypass.mixins.minecraft.AccessorPlayerList;
import com.mojang.authlib.GameProfile;

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

    /**
     * Looks up a player's UUID by name.
     *
     * <p>
     * The profile cache is consulted first so that anyone who has ever joined this server can be
     * listed, not just whoever happens to be online right now.
     *
     * @return null when the name is unknown to the server, in which case only the name is recorded.
     */
    @Nullable
    public static UUID uuidOf(MinecraftServer server, String name) {
        Optional<GameProfile> cached = server.getProfileCache() == null
                ? Optional.empty()
                : server.getProfileCache().get(name);
        if (cached.isPresent()) {
            return cached.get().getId();
        }
        ServerPlayer player = server.getPlayerList().getPlayerByName(name);
        return player == null ? null : player.getGameProfile().getId();
    }

    /** Replaces the server description shown in the server list. */
    public static void setMotd(MinecraftServer server, String motd) {
        server.setMotd(motd);
    }

    public static String getMotd(MinecraftServer server) {
        return server.getMotd();
    }

    /**
     * Disconnects everyone who is not allowed to stay during maintenance.
     *
     * @return the number of players kicked.
     */
    public static int kickDisallowed(PlayerList playerList, String reason) {
        int kicked = 0;
        // Copy first: disconnecting mutates the live player list.
        for (ServerPlayer player : new ArrayList<>(playerList.getPlayers())) {
            GameProfile profile = player.getGameProfile();
            if (OpBypassRegistry.isAllowedDuringMaintenance(profile.getId(), profile.getName())) {
                continue;
            }
            player.connection.disconnect(Component.literal(reason));
            kicked++;
        }
        return kicked;
    }
}
