package com.ayyan.askme.feature;

import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.regex.Pattern;

/**
 * Remembers the last 20 chat lines where someone wrote your name (with the time), so you can read
 * them later with /askme mentions. It only listens: it never hides or changes a message.
 */
public final class Mentions {

    private static final int MAX = 20;
    private static final ArrayDeque<String> LIST = new ArrayDeque<>();
    private static String cachedIgn;
    private static Pattern mentionPattern;
    private static Pattern ownLinePattern;

    private Mentions() {
    }

    public static void initialize() {
        ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> {
            if (!overlay) {
                record(message);
            }
            return true;
        });
        ClientReceiveMessageEvents.ALLOW_CHAT.register((message, signed, sender, params, time) -> {
            record(message);
            return true;
        });
    }

    private static synchronized void record(Component message) {
        try {
            Minecraft client = Minecraft.getInstance();
            if (message == null || client.player == null) {
                return;
            }
            String ign = client.player.getName().getString();
            if (ign.isBlank()) {
                return;
            }
            String line = message.getString();
            if (line == null || line.contains("[Ask Me]")) {
                return;
            }
            if (!ign.equals(cachedIgn)) {
                cachedIgn = ign;
                String quoted = Pattern.quote(ign);
                mentionPattern = Pattern.compile("(?i)(?<![A-Za-z0-9_])" + quoted + "(?![A-Za-z0-9_])");
                // "<Name> hi", "[VIP] Name: hi", "Name » hi" = your own line
                ownLinePattern = Pattern.compile("(?i)^(?:\\[[^\\]]*\\]\\s*)*<?" + quoted + ">?\\s*[:\u00bb>]");
            }
            String clean = line.replaceAll("\\s+", " ").trim();
            if (!mentionPattern.matcher(clean).find() || ownLinePattern.matcher(clean).find()) {
                return;
            }
            if (clean.length() > 140) {
                clean = clean.substring(0, 137) + "...";
            }
            String time = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"));
            LIST.addLast("[" + time + "] " + clean);
            while (LIST.size() > MAX) {
                LIST.pollFirst();
            }
        } catch (Throwable ignored) {
            // a listener must never disturb the game
        }
    }

    public static synchronized String text() {
        return LIST.isEmpty()
                ? "Nobody has written your name yet."
                : "Your name was written (" + LIST.size() + "):\n" + String.join("\n", LIST);
    }

    public static synchronized void clear() {
        LIST.clear();
    }
}
