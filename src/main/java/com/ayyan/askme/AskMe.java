package com.ayyan.askme;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Main entrypoint of the "Ask Me" mod. Everything interactive lives in {@link AskMeClient}. */
public class AskMe implements ModInitializer {

    public static final String MOD_ID = "ask-me";

    /** Release version kept in sync with gradle.properties. */
    public static final String VERSION = "1.3.2";

    public static final Logger LOGGER = LoggerFactory.getLogger("ask-me");

    @Override
    public void onInitialize() {
        LOGGER.info("[Ask Me] {} main entrypoint ready (client-only feature set).", VERSION);
    }
}
