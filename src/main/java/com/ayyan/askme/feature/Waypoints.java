package com.ayyan.askme.feature;

import com.ayyan.askme.config.AskMeConfig;
import com.ayyan.askme.scanner.ServerCommandScanner;
import net.minecraft.client.Minecraft;

import java.util.Locale;
import java.util.Map;

/**
 * Saved places ("home", "farm", "base"). They are saved per server / world, with X Y Z, and the list
 * shows how far each place is from where you stand now.
 */
public final class Waypoints {

    private static final int MAX_PER_SERVER = 50;

    private Waypoints() {
    }

    private static String prefix(Minecraft client) {
        return ServerCommandScanner.getCurrentServerKey(client) + "|";
    }

    private static String clean(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }

    public static String save(Minecraft client, String rawName) {
        String name = clean(rawName);
        if (client.player == null) {
            return "You are not in a world.";
        }
        if (name.isEmpty() || name.length() > 24 || name.contains("|")) {
            return "Give a short name (max 24 letters), for example: /askme wp save home";
        }
        AskMeConfig config = AskMeConfig.get();
        String key = prefix(client) + name;
        if (!config.waypoints.containsKey(key)) {
            long count = config.waypoints.keySet().stream().filter(k -> k.startsWith(prefix(client))).count();
            if (count >= MAX_PER_SERVER) {
                return "You already have " + MAX_PER_SERVER + " places here. Delete one first.";
            }
        }
        int x = client.player.getBlockX();
        int y = client.player.getBlockY();
        int z = client.player.getBlockZ();
        config.waypoints.put(key, new int[] {x, y, z});
        config.save();
        return "Saved '" + name + "': X " + x + "  Y " + y + "  Z " + z;
    }

    public static String delete(Minecraft client, String rawName) {
        String name = clean(rawName);
        AskMeConfig config = AskMeConfig.get();
        if (name.isEmpty() || config.waypoints.remove(prefix(client) + name) == null) {
            return "No saved place named '" + name + "'. Use /askme wp list.";
        }
        config.save();
        return "Deleted '" + name + "'.";
    }

    /** X Y Z of a saved place on this server, or null. */
    public static int[] find(Minecraft client, String rawName) {
        int[] p = AskMeConfig.get().waypoints.get(prefix(client) + clean(rawName));
        return p != null && p.length >= 3 ? p : null;
    }

    /** Deletes all saved places of the current server / world. */
    public static String clearAll(Minecraft client) {
        AskMeConfig config = AskMeConfig.get();
        String prefix = prefix(client);
        int before = config.waypoints.size();
        config.waypoints.keySet().removeIf(key -> key.startsWith(prefix));
        int removed = before - config.waypoints.size();
        if (removed > 0) {
            config.save();
        }
        return removed == 0 ? "There were no saved places here." : "Deleted " + removed + " saved places.";
    }

    public static String list(Minecraft client) {
        String prefix = prefix(client);
        StringBuilder out = new StringBuilder();
        int count = 0;
        for (Map.Entry<String, int[]> e : AskMeConfig.get().waypoints.entrySet()) {
            int[] p = e.getValue();
            if (!e.getKey().startsWith(prefix) || p == null || p.length < 3) {
                continue;
            }
            out.append(count == 0 ? "Saved places:" : "").append("\n  ")
                    .append(e.getKey().substring(prefix.length()))
                    .append(": X ").append(p[0]).append("  Y ").append(p[1]).append("  Z ").append(p[2]);
            if (client.player != null) {
                double dx = p[0] - client.player.getBlockX();
                double dy = p[1] - client.player.getBlockY();
                double dz = p[2] - client.player.getBlockZ();
                out.append("   (").append(Math.round(Math.sqrt(dx * dx + dy * dy + dz * dz))).append(" blocks away)");
            }
            count++;
        }
        return count == 0 ? "No saved places here yet. Use /askme wp save <name>." : out.toString();
    }
}
