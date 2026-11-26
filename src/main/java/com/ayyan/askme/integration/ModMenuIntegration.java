package com.ayyan.askme.integration;

import com.ayyan.askme.gui.AskMeConfigScreen;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/** Mod Menu "Configure" button opens the settings screen directly. */
public class ModMenuIntegration implements ModMenuApi {

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return AskMeConfigScreen::new;
    }
}
