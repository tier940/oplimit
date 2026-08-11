package com.github.gtexpert.testmod.api.util;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.github.gtexpert.testmod.Tags;

public class ModLog {

    private ModLog() {}

    public static final Logger logger = LogManager.getLogger(Tags.MODNAME);
}
