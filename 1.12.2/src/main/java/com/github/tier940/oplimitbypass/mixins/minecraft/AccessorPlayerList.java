package com.github.tier940.oplimitbypass.mixins.minecraft;

import net.minecraft.server.management.PlayerList;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes {@code PlayerList.maxPlayers} so the player cap can be changed without restarting.
 *
 * <p>
 * Vanilla reads {@code max-players} from server.properties exactly once, while the player list is
 * being constructed, and never offers a setter.
 */
@Mixin(PlayerList.class)
public interface AccessorPlayerList {

    @Mutable
    @Accessor("maxPlayers")
    void oplimit$setMaxPlayers(int maxPlayers);
}
