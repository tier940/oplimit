package com.github.tier940.oplimitbypass;

import java.io.File;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerAboutToStartEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.IExtensionPoint;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.github.tier940.oplimitbypass.server.OpBypassCounter;
import com.mojang.authlib.GameProfile;
import com.github.tier940.oplimitbypass.server.OpBypassRegistry;
import com.github.tier940.oplimitbypass.server.OpLimitLang;
import com.github.tier940.oplimitbypass.server.OpLimitCommand;

/**
 * Entry point.
 *
 * <p>
 * The interesting part of this mod is applied by Mixin long before this class is constructed; all that
 * is left here is pointing the registry at ops.json and registering the command.
 */
@Mod(OpLimitBypassMod.MODID)
public class OpLimitBypassMod {

    public static final String MODID = "oplimitbypass";
    public static final Logger LOGGER = LogManager.getLogger("OpLimitBypass");

    public OpLimitBypassMod() {
        MinecraftForge.EVENT_BUS.register(this);

        // This mod only ever runs on the server: everything it does is in the login check and the
        // player count. Without this a vanilla-ish client is told the server has a mod it does not,
        // and the connection is refused over a difference that cannot matter here.
        ModLoadingContext.get().registerExtensionPoint(
                IExtensionPoint.DisplayTest.class,
                () -> new IExtensionPoint.DisplayTest(
                        () -> IExtensionPoint.DisplayTest.IGNORESERVERONLY,
                        (remoteVersion, isFromServer) -> true));
    }

    @SubscribeEvent
    public void onServerAboutToStart(ServerAboutToStartEvent event) {
        // The config directory always sits directly inside the server root, which is where ops.json lives.
        File configDir = FMLPaths.CONFIGDIR.get().toFile();
        File serverRoot = configDir.getParentFile();
        // Language for the server-resolved fallback text. Clients that have this mod translate
        // the keys themselves and ignore it.
        OpLimitLang.load(System.getProperty("oplimit.lang"));
        OpBypassRegistry.init(serverRoot != null ? serverRoot : new File("."), OpBypassCounter.PROFILE_READER);
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        OpLimitCommand.register(event.getDispatcher());
    }

    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        // A player can be added to the maintenance list before the server has ever seen them; this
        // fills in their UUID the first time they actually connect.
        GameProfile profile = event.getEntity().getGameProfile();
        if (profile.getId() != null && profile.getName() != null) {
            OpBypassRegistry.rememberMaintenanceUuid(profile.getName(), profile.getId());
        }
    }

    @SubscribeEvent
    public void onServerStopped(ServerStoppedEvent event) {
        OpBypassRegistry.shutdown();
    }
}
