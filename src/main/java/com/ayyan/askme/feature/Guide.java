package com.ayyan.askme.feature;

import com.ayyan.askme.chat.NameHighlighter;
import com.ayyan.askme.util.ChatUtil;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.lang.reflect.Method;

/**
 * Walking guide to a place. You give X Y Z (or a saved place, or your last death) and an arrow with the
 * distance is shown above your hotbar, always pointing to the target. When you come close, the guide
 * clears itself. You can also clear it by hand with /askme goto clear.
 *
 * <p>Only the player's own position and direction are used, so it works on every server.</p>
 */
public final class Guide {

    private static final double ARRIVE_BLOCKS = 5.0;
    private static final int GOLD = 0xFFD54F;
    private static final int AQUA = 0x55E6FF;
    private static final int GRAY = 0xB8B8B8;

    private static boolean active;
    private static String label = "";
    private static int targetX;
    private static int targetY;
    private static int targetZ;
    private static int tickCounter;

    private static boolean overlayLooked;
    private static Method overlayMethod;
    private static Object overlayOwner;
    private static boolean overlayBooleanValue;

    private Guide() {
    }

    public static void initialize() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            try {
                update(client);
            } catch (Throwable ignored) {
                // the guide must never disturb the game
            }
        });
    }

    public static boolean isActive() {
        return active;
    }

    public static String start(Minecraft client, String name, int x, int y, int z) {
        if (client.player == null) {
            return "You are not in a world.";
        }
        active = true;
        label = name == null || name.isBlank() ? "target" : name;
        targetX = x;
        targetY = y;
        targetZ = z;
        tickCounter = 0;
        double dist = distance(client);
        return "Guide to " + label + " (X " + x + "  Y " + y + "  Z " + z + "), " + Math.round(dist)
                + " blocks away. Follow the arrow above your hotbar. It clears by itself when you arrive, "
                + "or use /askme goto clear.";
    }

    public static String clear() {
        if (!active) {
            return "No guide is running.";
        }
        active = false;
        return "Guide cleared.";
    }

    // ------------------------------------------------------------------ per tick

    private static double distance(Minecraft client) {
        double dx = targetX + 0.5 - client.player.getX();
        double dy = targetY - client.player.getY();
        double dz = targetZ + 0.5 - client.player.getZ();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static void update(Minecraft client) {
        if (!active) {
            return;
        }
        if (client.player == null) {
            active = false; // you left the world
            return;
        }
        if (++tickCounter % 4 != 0) {
            return;
        }

        double dx = targetX + 0.5 - client.player.getX();
        double dy = targetY - client.player.getY();
        double dz = targetZ + 0.5 - client.player.getZ();
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);

        if (dist <= ARRIVE_BLOCKS) {
            active = false;
            ChatUtil.info(client, "You reached " + label + ". Guide cleared.");
            NameHighlighter.playSoundNow(client, "entity.player.levelup", 100);
            return;
        }

        // Minecraft yaw: 0 = south, 90 = west. Positive difference = target is on your right.
        double targetYaw = Math.toDegrees(Math.atan2(-dx, dz));
        double relative = wrap(targetYaw - client.player.getYRot());
        String arrow = arrowFor(relative);

        String vertical = "";
        if (dy > 3) {
            vertical = "  (" + Math.round(dy) + " up)";
        } else if (dy < -3) {
            vertical = "  (" + Math.round(-dy) + " down)";
        }

        MutableComponent text = Component.literal("");
        text.append(styled("\u25B8 " + label + "   ", GOLD, true));
        text.append(styled(arrow + "   ", AQUA, true));
        text.append(styled(Math.round(dist) + " blocks" + vertical, GRAY, false));
        showOverlay(client, text);
    }

    private static double wrap(double degrees) {
        double d = degrees % 360.0;
        if (d >= 180.0) {
            d -= 360.0;
        } else if (d < -180.0) {
            d += 360.0;
        }
        return d;
    }

    private static String arrowFor(double relative) {
        double a = Math.abs(relative);
        boolean right = relative > 0;
        if (a <= 22.5) {
            return "\u2191";
        }
        if (a <= 67.5) {
            return right ? "\u2197" : "\u2196";
        }
        if (a <= 112.5) {
            return right ? "\u2192" : "\u2190";
        }
        if (a <= 157.5) {
            return right ? "\u2198" : "\u2199";
        }
        return "\u2193";
    }

    private static MutableComponent styled(String text, int rgb, boolean bold) {
        return Component.literal(text).withStyle(style -> style.withColor(rgb).withBold(bold));
    }

    // ------------------------------------------------------------------ text above the hotbar

    /**
     * Shows a short text above the hotbar. The method name differs between game versions, so it is found
     * by reflection. If none works, the text is printed to the chat every 3 seconds instead.
     */
    private static void showOverlay(Minecraft client, Component text) {
        if (!overlayLooked) {
            overlayLooked = true;
            findOverlayMethod(client);
        }
        if (overlayMethod != null) {
            try {
                if (overlayMethod.getParameterCount() == 2) {
                    overlayMethod.invoke(overlayOwner, text, overlayBooleanValue);
                } else {
                    overlayMethod.invoke(overlayOwner, text);
                }
                return;
            } catch (Throwable problem) {
                overlayMethod = null;
            }
        }
        if (tickCounter % 60 == 0) {
            ChatUtil.subtle(client, text.getString());
        }
    }

    private static void findOverlayMethod(Minecraft client) {
        Object[] owners = {client.gui, client.player};
        for (Object owner : owners) {
            if (owner == null) {
                continue;
            }
            for (Method method : owner.getClass().getMethods()) {
                String name = method.getName();
                Class<?>[] types = method.getParameterTypes();
                boolean overlayName = name.equals("setOverlayMessage") || name.equals("sendOverlayMessage")
                        || name.equals("displayClientMessage");
                if (!overlayName || types.length == 0 || types[0] != Component.class) {
                    continue;
                }
                if (types.length == 1 || (types.length == 2 && types[1] == boolean.class)) {
                    overlayMethod = method;
                    overlayOwner = owner;
                    // displayClientMessage(text, true) = above the hotbar; setOverlayMessage(text, false) = no rainbow
                    overlayBooleanValue = name.equals("displayClientMessage");
                    return;
                }
            }
        }
    }
}
