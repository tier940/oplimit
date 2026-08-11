package com.github.tier940.oplimitbypass.mixins.minecraft;

import net.minecraft.server.players.PlayerList;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes {@code PlayerList.maxPlayers} so the player cap can be changed without restarting.
 */
@Mixin(PlayerList.class)
public interface AccessorPlayerList {

    @Mutable
    @Accessor("maxPlayers")
    void oplimit$setMaxPlayers(int maxPlayers);
}
