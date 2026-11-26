package com.ayyan.askme.feature;

import com.ayyan.askme.chat.NameHighlighter;
import com.ayyan.askme.config.AskMeConfig;
import com.ayyan.askme.util.ChatUtil;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Two small alerts that watch the game for you:
 * <ul>
 *   <li>Low health: a warning and a sound when you have 3 hearts or less.</li>
 *   <li>Friend alert: a message and a sound when a friend joins or leaves your server.</li>
 * </ul>
 * Both can be switched off with /askme lowhealth off and /askme friendalert off.
 */
public final class GameAlerts {

    private static final float LOW_HEALTH = 6.0f;
    private static final float RECOVERED_HEALTH = 10.0f;
    private static final long HEALTH_GAP_MS = 15_000L;

    private static boolean healthAlerted;
    private static long lastHealthAlertAt;
    private static int tickCounter;
    private static boolean onlineReady;
    private static final Set<String> LAST_ONLINE = new HashSet<>();

    private GameAlerts() {
    }

    public static void initialize() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            try {
                tick(client);
            } catch (Throwable ignored) {
                // an alert must never disturb the game
            }
        });
    }

    private static void tick(Minecraft client) {
        if (client.player == null || client.getConnection() == null) {
            healthAlerted = false;
            onlineReady = false;
            LAST_ONLINE.clear();
            return;
        }
        AskMeConfig config = AskMeConfig.get();
        if (config.lowHealthAlert) {
            checkHealth(client);
        }
        if (config.friendAlert && !config.friends.isEmpty()) {
            if (++tickCounter % 40 == 0) {
                checkFriends(client, config);
            }
        } else {
            onlineReady = false;
        }
    }

    private static void checkHealth(Minecraft client) {
        float hp = client.player.getHealth();
        if (hp <= 0f) {
            return;
        }
        if (hp <= LOW_HEALTH) {
            long now = System.currentTimeMillis();
            if (!healthAlerted && now - lastHealthAlertAt > HEALTH_GAP_MS) {
                healthAlerted = true;
                lastHealthAlertAt = now;
                ChatUtil.warn(client, "Low health! About " + (int) Math.ceil(hp / 2.0f) + " hearts left.");
                NameHighlighter.playSoundNow(client, "block.note_block.bass", 100);
            }
        } else if (hp >= RECOVERED_HEALTH) {
            healthAlerted = false;
        }
    }

    private static void checkFriends(Minecraft client, AskMeConfig config) {
        Set<String> now = new HashSet<>();
        for (var info : client.getConnection().getOnlinePlayers()) {
            now.add(info.getProfile().name().toLowerCase(Locale.ROOT));
        }
        if (onlineReady) {
            for (String friend : config.friends) {
                boolean was = LAST_ONLINE.contains(friend);
                boolean is = now.contains(friend);
                if (is && !was) {
                    ChatUtil.info(client, friend + " joined the server.");
                    NameHighlighter.playSoundNow(client, config.nameSound, config.nameSoundVolume);
                } else if (was && !is) {
                    ChatUtil.subtle(client, friend + " left the server.");
                }
            }
        }
        LAST_ONLINE.clear();
        LAST_ONLINE.addAll(now);
        onlineReady = true;
    }

    // ------------------------------------------------------------------ friend list commands

    public static String friendAdd(String rawName) {
        String name = rawName == null ? "" : rawName.trim().toLowerCase(Locale.ROOT);
        if (!name.matches("[a-z0-9_]{3,16}")) {
            return "A player name has 3 to 16 letters, numbers or _.";
        }
        AskMeConfig config = AskMeConfig.get();
        if (config.friends.contains(name)) {
            return name + " is already in your friend list.";
        }
        if (config.friends.size() >= 30) {
            return "The friend list is full (30). Remove someone first.";
        }
        config.friends.add(name);
        config.save();
        return name + " added. You will get an alert when they join or leave.";
    }

    public static String friendRemove(String rawName) {
        String name = rawName == null ? "" : rawName.trim().toLowerCase(Locale.ROOT);
        AskMeConfig config = AskMeConfig.get();
        if (!config.friends.remove(name)) {
            return name + " is not in your friend list.";
        }
        config.save();
        return name + " removed.";
    }

    public static String friendList() {
        AskMeConfig config = AskMeConfig.get();
        return config.friends.isEmpty()
                ? "Your friend list is empty. Use /askme friend add <name>."
                : "Friends: " + String.join(", ", config.friends);
    }
}
