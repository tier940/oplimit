package com.github.tier940.oplimitbypass.mixins.minecraft;

import net.minecraft.network.protocol.status.ServerStatus;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.players.PlayerList;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import com.github.tier940.oplimitbypass.server.OpBypassRegistry;

/**
 * Keeps the server list ping in step with the limit that is actually enforced.
 *
 * <p>
 * {@code buildPlayerStatus} reads {@code getPlayers().size()} directly instead of going through
 * {@link PlayerList#getPlayerCount()}, so redirecting that method alone leaves the ping counting
 * bypassing operators. A server at max-players=1 holding one bypassing operator would advertise
 * "1/1" and look full while its slot is in fact still free.
 *
 * <p>
 * The value is changed at the {@code ServerStatus.Players} constructor rather than at the
 * {@code List.size()} calls: that method calls size() four times and only the count reaching this
 * argument is the one being reported, the others size the sample list of player names.
 */
@Mixin(MinecraftServer.class)
public abstract class MixinServerStatusPlayerCount {

    @ModifyArg(
            method = "buildPlayerStatus",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/network/protocol/status/ServerStatus$Players;<init>(IILjava/util/List;)V"),
            index = 1)
    private int oplimit$reportNonBypassingCount(int online) {
        return OpBypassRegistry.countNonBypassing(((MinecraftServer) (Object) this).getPlayerList().getPlayers());
    }
}
