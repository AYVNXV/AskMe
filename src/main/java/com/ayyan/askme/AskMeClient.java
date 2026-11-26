package com.ayyan.askme;

import com.ayyan.askme.chat.NameHighlighter;
import com.ayyan.askme.control.ClientControlService;
import com.ayyan.askme.diagnostics.DeathExplainer;
import com.ayyan.askme.diagnostics.ModDoctor;
import com.ayyan.askme.feature.GameAlerts;
import com.ayyan.askme.feature.Guide;
import com.ayyan.askme.feature.Mentions;
import com.ayyan.askme.command.AiCommands;
import com.ayyan.askme.command.ManageCommands;
import com.ayyan.askme.config.AskMeConfig;
import com.ayyan.askme.keybind.ModKeys;
import com.ayyan.askme.scanner.ServerCommandScanner;
import net.fabricmc.api.ClientModInitializer;

public class AskMeClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        AskMeConfig.load();
        ModKeys.initialize();
        AiCommands.initialize();
        ManageCommands.initialize();
        NameHighlighter.initialize();
        ClientControlService.initialize();
        ModDoctor.initialize();
        DeathExplainer.initialize();
        ServerCommandScanner.initialize();
        GameAlerts.initialize();
        Guide.initialize();
        Mentions.initialize();

        AskMe.LOGGER.info("[Ask Me] Client initialized.");
    }
}