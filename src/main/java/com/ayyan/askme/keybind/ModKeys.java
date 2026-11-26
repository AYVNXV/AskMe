package com.ayyan.askme.keybind;

import com.ayyan.askme.AskMe;
import com.ayyan.askme.gui.AskMeChatScreen;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

/**
 * Registers the default "K" key. K opens the Ask Me chat window (settings are one click away
 * in its top bar). Pressing K again while the window is open closes it.
 */
public final class ModKeys {

    private static KeyMapping openChatKey;

    private ModKeys() {
    }

    public static void initialize() {
        KeyMapping.Category category = KeyMapping.Category.register(
                Identifier.fromNamespaceAndPath(AskMe.MOD_ID, "main"));

        openChatKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.ask-me.open_chat",
                InputConstants.Type.KEYSYM,
                InputConstants.KEY_K,
                category));

        ClientTickEvents.END_CLIENT_TICK.register(ModKeys::onEndClientTick);
    }

    private static void onEndClientTick(Minecraft client) {
        while (openChatKey.consumeClick()) {
            if (client.player == null) {
                return;
            }
            if (client.gui.screen() instanceof AskMeChatScreen) {
                client.gui.setScreen(null);
            } else if (client.gui.screen() == null) {
                client.gui.setScreen(new AskMeChatScreen(null));
            }
        }
    }
}
