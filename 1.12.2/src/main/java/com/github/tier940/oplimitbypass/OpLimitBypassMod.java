package com.github.tier940.oplimitbypass;

import java.io.File;

import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerAboutToStartEvent;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import net.minecraftforge.fml.common.event.FMLServerStoppedEvent;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.github.tier940.oplimitbypass.server.CommandOpLimit;
import com.github.tier940.oplimitbypass.server.OpBypassCounter;
import com.mojang.authlib.GameProfile;
import com.github.tier940.oplimitbypass.server.OpBypassRegistry;
import com.github.tier940.oplimitbypass.server.OpLimitLang;

/**
 * Entry point.
 *
 * <p>
 * The interesting part of this mod is applied by Mixin long before this class is constructed; all that
 * is left here is pointing the registry at ops.json and registering the command.
 */
@Mod(
     modid = Tags.MODID,
     name = Tags.MODNAME,
     version = Tags.VERSION,
     acceptableRemoteVersions = "*",
     dependencies = "required-after:mixinbooter@[10.6,);")
public class OpLimitBypassMod {

    public static final Logger LOGGER = LogManager.getLogger(Tags.MODNAME);

    /** Registered once at load: a server can start and stop repeatedly within one process. */
    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        MinecraftForge.EVENT_BUS.register(this);
    }

    @Mod.EventHandler
    public void serverAboutToStart(FMLServerAboutToStartEvent event) {
        // The config directory always sits directly inside the server root, which is where ops.json lives.
        // Deriving it this way avoids depending on any obfuscated MinecraftServer method.
        File serverRoot = Loader.instance().getConfigDir().getParentFile();
        // Language for the server-resolved fallback text. Clients that have this mod translate
        // the keys themselves and ignore it.
        OpLimitLang.load(System.getProperty("oplimit.lang"));
        OpBypassRegistry.init(serverRoot != null ? serverRoot : new File("."), OpBypassCounter.PROFILE_READER);
    }

    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        event.registerServerCommand(new CommandOpLimit());
    }

    /**
     * A player can be added to the maintenance list before the server has ever seen them; this
     * fills in their UUID the first time they actually connect.
     */
    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        GameProfile profile = event.player.getGameProfile();
        if (profile.getId() != null && profile.getName() != null) {
            OpBypassRegistry.rememberMaintenanceUuid(profile.getName(), profile.getId());
        }
    }

    @Mod.EventHandler
    public void serverStopped(FMLServerStoppedEvent event) {
        OpBypassRegistry.shutdown();
    }
}
