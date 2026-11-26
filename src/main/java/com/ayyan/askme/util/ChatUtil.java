package com.ayyan.askme.util;

import com.ayyan.askme.chat.ChatLog;
import com.ayyan.askme.gui.AskMeChatScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * Central output helper. Every message is (1) stored in the Ask Me chat window log and
 * (2) printed to the LOCAL chat HUD - unless the Ask Me window is open, which already shows it.
 * Nothing here sends packets, so output stays private to the local player.
 */
public final class ChatUtil {

    private static final String PREFIX = "[Ask Me] ";

    private ChatUtil() {
    }

    // ChatFormatting was gutted in 26.2, so plain RGB colours via Style are used instead.
    private static final int AQUA = 0x55FFFF;
    private static final int WHITE = 0xFFFFFF;
    private static final int PURPLE = 0xFF55FF;
    private static final int GOLD = 0xFFAA00;
    private static final int YELLOW = 0xFFFF55;
    private static final int RED = 0xFF5555;
    private static final int DARK_GRAY = 0x555555;
    private static final int GRAY = 0xAAAAAA;

    private static MutableComponent text(String value, int rgb, boolean italic) {
        return Component.literal(value).withStyle(style -> style.withColor(rgb).withItalic(italic));
    }

    public static void info(Minecraft client, String message) {
        ChatLog.add(ChatLog.Kind.SYSTEM, message);
        hud(client, text(PREFIX, AQUA, false).append(text(message, WHITE, false)));
    }

    public static void ai(Minecraft client, String message) {
        hud(client, text(PREFIX, PURPLE, false)
                .append(text("AI: ", AQUA, false))
                .append(text(message, WHITE, false)));
    }

    public static void warn(Minecraft client, String message) {
        ChatLog.add(ChatLog.Kind.SYSTEM, message);
        hud(client, text(PREFIX, GOLD, false).append(text(message, YELLOW, false)));
    }

    public static void error(Minecraft client, String message) {
        ChatLog.add(ChatLog.Kind.SYSTEM, message);
        hud(client, text(PREFIX, RED, false).append(text(message, RED, false)));
    }

    public static void subtle(Minecraft client, String message) {
        ChatLog.add(ChatLog.Kind.SYSTEM, message);
        hud(client, text(PREFIX, DARK_GRAY, true).append(text(message, GRAY, true)));
    }

    /** Pre-composed rich text (for example /askme info). Logged as plain text too. */
    public static void raw(Minecraft client, Component message) {
        ChatLog.add(ChatLog.Kind.SYSTEM, message.getString());
        hud(client, message);
    }

    /** Prints to the HUD chat only (no log entry). Skipped while the Ask Me window is open. */
    public static void hud(Minecraft client, Component message) {
        if (client == null || client.player == null) {
            return;
        }
        if (client.gui.screen() instanceof AskMeChatScreen) {
            return;
        }
        client.player.sendSystemMessage(message);
    }
}