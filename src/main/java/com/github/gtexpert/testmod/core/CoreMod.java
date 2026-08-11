package com.github.gtexpert.testmod.core;

import java.io.*;
import java.util.Map;
import java.util.Properties;

import net.minecraftforge.fml.relauncher.IFMLLoadingPlugin;

import org.jetbrains.annotations.Nullable;

import com.github.gtexpert.testmod.api.ModValues;
import com.github.gtexpert.testmod.api.util.ModLog;

public class CoreMod implements IFMLLoadingPlugin {

    static Properties coremodConfig = new Properties();
    public static boolean downloadOnlyOnce;

    @Override
    public String[] getASMTransformerClass() {
        ModLog.logger.info("CoreMod: getASMTransformerClass() called");
        return new String[] {};
    }

    @Override
    public String getModContainerClass() {
        return null;
    }

    @Nullable
    @Override
    public String getSetupClass() {
        ModLog.logger.info("CoreMod: getSetupClass() called, returning DepLoader");
        return null;
    }

    @Override
    public void injectData(Map<String, Object> data) {
        ModLog.logger.info("CoreMod: injectData() called");
        coremodConfig.setProperty("downloadOnlyOnce", "true");
        File mcLocation = (File) data.get("mcLocation");
        File configDir = new File(mcLocation, "config");
        // noinspection ResultOfMethodCallIgnored
        configDir.mkdir();
        File config = new File(configDir, ModValues.MODID + "/CoreMod.properties");
        try (Reader r = new FileReader(config)) {
            coremodConfig.load(r);
        } catch (FileNotFoundException ignored) {
            // not a problem
        } catch (IOException e) {
            ModLog.logger.warn("Can't read coremod config. Proceeding with defaults!", e);
        }
        try (Writer r = new FileWriter(config)) {
            coremodConfig.store(r, "Config file for GTExpert CoreMod");
        } catch (IOException e) {
            ModLog.logger.warn("Can't write coremod config. Changes may not have been saved!", e);
        }
        downloadOnlyOnce = "true".equalsIgnoreCase(coremodConfig.getProperty("downloadOnlyOnce"));
    }

    @Override
    public String getAccessTransformerClass() {
        return null;
    }
}
