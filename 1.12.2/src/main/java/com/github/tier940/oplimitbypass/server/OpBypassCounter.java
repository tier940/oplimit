package com.github.tier940.oplimitbypass.server;

import java.util.ArrayList;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.management.PlayerList;
import net.minecraft.util.text.TextComponentString;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.github.tier940.oplimitbypass.mixins.minecraft.AccessorPlayerList;
import com.mojang.authlib.GameProfile;

/**
 * The 1.12.2 half of the registry: the few operations that have to name a Minecraft type.
 *
 * <p>
 * {@link OpBypassRegistry} is shared verbatim with the 1.20.1 build, so it cannot reference
 * {@code EntityPlayerMP} or {@code PlayerList}. It only ever needs one thing from a Minecraft version
 * — how to get a profile out of a player — which {@link #PROFILE_READER} supplies once at startup.
 * The two methods below are called from the command class and never from shared code.
 */
public final class OpBypassCounter {

    /** 1.12.2 exposes the profile directly on the player entity. */
    public static final OpBypassRegistry.ProfileReader PROFILE_READER = player -> player instanceof EntityPlayerMP
            ? ((EntityPlayerMP) player).getGameProfile()
            : null;

    private OpBypassCounter() {}

    /** Pushes the on-disk operator list back into the running server. Best effort. */
    public static void reloadVanillaOps(@NotNull MinecraftServer server) {
        try {
            server.getPlayerList().getOppedPlayers().readSavedFile();
        } catch (Exception e) {
            OpBypassRegistry.LOGGER.warn("Could not refresh the vanilla operator list", e);
        }
    }

    /** Changes the player cap in memory. Not persisted, server.properties still wins on restart. */
    public static void setMaxPlayers(@NotNull PlayerList playerList, int maxPlayers) {
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
    public static UUID uuidOf(@NotNull MinecraftServer server, @NotNull String name) {
        if (server.getPlayerProfileCache() != null) {
            GameProfile cached = server.getPlayerProfileCache().getGameProfileForUsername(name);
            if (cached != null && cached.getId() != null) {
                return cached.getId();
            }
        }
        EntityPlayerMP player = server.getPlayerList().getPlayerByUsername(name);
        return player == null ? null : player.getGameProfile().getId();
    }

    /** Replaces the server description shown in the server list. */
    public static void setMotd(@NotNull MinecraftServer server, @NotNull String motd) {
        server.setMOTD(motd);
    }

    @NotNull
    public static String getMotd(@NotNull MinecraftServer server) {
        return server.getMOTD();
    }

    /**
     * Disconnects everyone who is not allowed to stay during maintenance.
     *
     * @return the number of players kicked.
     */
    public static int kickDisallowed(@NotNull PlayerList playerList, @NotNull String reason) {
        int kicked = 0;
        // Copy first: disconnecting mutates the live player list.
        for (EntityPlayerMP player : new ArrayList<>(playerList.getPlayers())) {
            GameProfile profile = player.getGameProfile();
            if (OpBypassRegistry.isAllowedDuringMaintenance(profile.getId(), profile.getName())) {
                continue;
            }
            player.connection.disconnect(new TextComponentString(reason));
            kicked++;
        }
        return kicked;
    }
}
