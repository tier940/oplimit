package com.github.tier940.oplimitbypass.mixins.minecraft;

import java.net.SocketAddress;
import java.util.List;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.management.PlayerList;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.github.tier940.oplimitbypass.server.OpBypassRegistry;
import com.mojang.authlib.GameProfile;

/**
 * Stops operators with {@code bypassesPlayerLimit} from consuming a player slot.
 *
 * <p>
 * Vanilla lets such an operator join a full server, but once connected they still occupy one of the
 * {@code max-players} slots, so every bypassing operator online lowers the cap for everyone else.
 * The redirect below replaces {@code this.playerEntityList.size()} inside
 * {@link PlayerList#allowUserToConnect(SocketAddress, GameProfile)} with a count that ignores
 * bypassing operators. The whitelist and ban checks earlier in that method are left untouched.
 *
 * <p>
 * {@code getCurrentPlayerCount()} is adjusted the same way so the reported figure matches the rule
 * actually being enforced. That method is what feeds the server list ping, the query protocol, the
 * dedicated server GUI and {@code /list}; without it a full server with operators online would
 * advertise a nonsensical "21/20".
 */
@Mixin(PlayerList.class)
public abstract class MixinPlayerListLimit {

    @Redirect(method = "allowUserToConnect", at = @At(value = "INVOKE", target = "Ljava/util/List;size()I"))
    private int oplimit$countTowardsLimit(List<EntityPlayerMP> players, SocketAddress address, GameProfile profile) {
        return OpBypassRegistry.countTowardsLimit(players, profile);
    }

    @Inject(method = "getCurrentPlayerCount", at = @At("HEAD"), cancellable = true)
    private void oplimit$hideBypassingOperators(CallbackInfoReturnable<Integer> cir) {
        List<EntityPlayerMP> players = ((PlayerList) (Object) this).getPlayers();
        cir.setReturnValue(OpBypassRegistry.countNonBypassing(players));
    }
}
