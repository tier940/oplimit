package com.github.tier940.oplimitbypass;

import java.io.File;

import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLServerAboutToStartEvent;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import net.minecraftforge.fml.common.event.FMLServerStoppedEvent;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.github.tier940.oplimitbypass.server.CommandOpLimit;
import com.github.tier940.oplimitbypass.server.OpBypassCounter;
import com.github.tier940.oplimitbypass.server.OpBypassRegistry;

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

    @Mod.EventHandler
    public void serverAboutToStart(FMLServerAboutToStartEvent event) {
        // The config directory always sits directly inside the server root, which is where ops.json lives.
        // Deriving it this way avoids depending on any obfuscated MinecraftServer method.
        File serverRoot = Loader.instance().getConfigDir().getParentFile();
        OpBypassRegistry.init(serverRoot != null ? serverRoot : new File("."), OpBypassCounter.PROFILE_READER);
    }

    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        event.registerServerCommand(new CommandOpLimit());
    }

    @Mod.EventHandler
    public void serverStopped(FMLServerStoppedEvent event) {
        OpBypassRegistry.shutdown();
    }
}
