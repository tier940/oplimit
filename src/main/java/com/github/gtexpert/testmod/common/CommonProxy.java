package com.github.gtexpert.testmod.common;

import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;

import com.github.gtexpert.testmod.api.ModValues;

@Mod.EventBusSubscriber(modid = ModValues.MODID)
public class CommonProxy {

    public void preInit(FMLPreInitializationEvent event) {}
}
