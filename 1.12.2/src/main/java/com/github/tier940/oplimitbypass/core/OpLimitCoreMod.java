package com.github.tier940.oplimitbypass.core;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import net.minecraftforge.fml.relauncher.IFMLLoadingPlugin;

import zone.rong.mixinbooter.IEarlyMixinLoader;

/**
 * Minimal FML loading plugin whose only job is handing the mixin config to MixinBooter.
 *
 * <p>
 * The config has to be queued from here rather than from an {@code ILateMixinLoader}: late mixins run
 * after most vanilla classes are already loaded, and a config targeting {@code PlayerList} would simply
 * be rejected at that point. MixinBooter documents {@code IEarlyMixinLoader} on the loading plugin as
 * the way to apply mixins to vanilla or Forge classes.
 *
 * <p>
 * No ASM transformer is registered; everything this mod does goes through Mixin.
 */
@IFMLLoadingPlugin.MCVersion("1.12.2")
@IFMLLoadingPlugin.Name("OpLimitBypassCore")
// FML sorts coremods ascending by sortIndex (default 0). 1001 puts us after the Forge-internal
// plugins while still being inside the LaunchWrapper phase, before any Minecraft class is touched.
@IFMLLoadingPlugin.SortingIndex(1001)
public class OpLimitCoreMod implements IFMLLoadingPlugin, IEarlyMixinLoader {

    // Hardcoded rather than derived from Tags.MODID: this class loads long before that is safe to touch.
    private static final List<String> MIXIN_CONFIGS =
            Collections.singletonList("mixins.oplimitbypass.minecraft.json");

    @Override
    public List<String> getMixinConfigs() {
        return MIXIN_CONFIGS;
    }

    @Override
    public String[] getASMTransformerClass() {
        return new String[0];
    }

    // Returning null tells FML that this jar does not act as its own ModContainer.
    // The @Mod-annotated class in the same jar fills that role instead.
    @Override
    public String getModContainerClass() {
        return null;
    }

    // No IFMLCallHook is needed; the mixin config is static.
    @Override
    public String getSetupClass() {
        return null;
    }

    @Override
    public void injectData(Map<String, Object> data) {}

    // This mod does not use an Access Transformer; returning null skips AT registration entirely.
    @Override
    public String getAccessTransformerClass() {
        return null;
    }
}
