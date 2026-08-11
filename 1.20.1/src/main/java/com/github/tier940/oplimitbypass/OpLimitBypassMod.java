package com.github.tier940.oplimitbypass;

import java.io.File;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerAboutToStartEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.github.tier940.oplimitbypass.server.OpBypassCounter;
import com.github.tier940.oplimitbypass.server.OpBypassRegistry;
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
    }

    @SubscribeEvent
    public void onServerAboutToStart(ServerAboutToStartEvent event) {
        // The config directory always sits directly inside the server root, which is where ops.json lives.
        File configDir = FMLPaths.CONFIGDIR.get().toFile();
        File serverRoot = configDir.getParentFile();
        OpBypassRegistry.init(serverRoot != null ? serverRoot : new File("."), OpBypassCounter.PROFILE_READER);
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        OpLimitCommand.register(event.getDispatcher());
    }

    @SubscribeEvent
    public void onServerStopped(ServerStoppedEvent event) {
        OpBypassRegistry.shutdown();
    }
}
