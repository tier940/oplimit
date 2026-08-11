package com.github.tier940.oplimitbypass.mixins.minecraft;

import net.minecraft.network.ServerStatusResponse;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.TextComponentString;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.github.tier940.oplimitbypass.server.OpBypassRegistry;

/**
 * Keeps the server description in the server list current.
 *
 * <p>
 * The MOTD is copied into the status response once during startup, so changing it at runtime with
 * {@code setMOTD} alone never reaches the server list. The player counts next to it are refreshed on
 * a five second tick, and this rides along with that so the maintenance description appears and
 * disappears with the mode.
 */
@Mixin(MinecraftServer.class)
public abstract class MixinServerStatusMotd {

    @Inject(method = "tick", at = @At("RETURN"))
    private void oplimit$refreshMotd(CallbackInfo ci) {
        MinecraftServer server = (MinecraftServer) (Object) this;
        ServerStatusResponse response = server.getServerStatusResponse();
        if (response == null) {
            return;
        }
        String motd = server.getMOTD();
        if (motd != null && !motd.equals(String.valueOf(response.getServerDescription()))) {
            response.setServerDescription(new TextComponentString(motd));
        }
    }
}
