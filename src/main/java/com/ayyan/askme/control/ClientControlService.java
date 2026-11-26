package com.ayyan.askme.control;

import com.ayyan.askme.AskMe;
import com.ayyan.askme.chat.NameHighlighter;
import com.ayyan.askme.diagnostics.DeathExplainer;
import com.ayyan.askme.diagnostics.ModDoctor;
import com.ayyan.askme.feature.Guide;
import com.ayyan.askme.feature.Mentions;
import com.ayyan.askme.feature.Waypoints;
import com.ayyan.askme.util.ChatUtil;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundSource;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ClientControlService {

    /** Moment the game (and this mod) started. Used by the playtime control. */
    private static final long SESSION_START_MS = System.currentTimeMillis();

    private static final Pattern FOV_PATTERN = Pattern.compile("\\bfov\\b\\D{0,12}(\\d{2,3})");
    private static final Pattern RENDER_PATTERN = Pattern.compile("\\b(?:render\\s*distance|chunks?)\\b\\D{0,12}(\\d{1,2})");
    private static final Pattern DURATION_PART = Pattern.compile(
            "(\\d+)\\s*(hours?|hrs?|ghanta|ghante|h|minutes?|mins?|minat|minute|m|seconds?|secs?|sec|s)(?![a-z])");
    private static final Pattern LEAVE_WORDS = Pattern.compile(
            "\\b(disconnect|kick|nikal|nikaal|dc)\\b|\\b(leave|quit)\\b.*\\bserver\\b");

    private static final Pattern LEADING_DURATION = Pattern.compile(
            "^(?:in\\s+)?((?:\\d+\\s*(?:hours?|hrs?|h|minutes?|mins?|m|seconds?|secs?|s)(?![a-z])\\s*)+)(.*)$");
    private static final Pattern CLOCK_TIME = Pattern.compile("^at\\s+(\\d{1,2}):(\\d{2})\\s*(.*)$");
    private static final Pattern REMIND_WORDS = Pattern.compile(
            "\\b(remind(?:er)?|yaad\\s*dil\\w*|yad\\s*dil\\w*|alarm)\\b");

    private record Reminder(long atMs, String text) {
    }

    private static final List<Reminder> REMINDERS = new ArrayList<>();

    /** A delay plus the text that follows it. */
    private record When(long delayMs, String rest) {
    }

    /** Time (ms) when the planned disconnect happens. 0 = nothing planned. */
    private static long disconnectAtMs;
    private static boolean warned60;
    private static boolean warned10;

    /** Option values saved by "optimize", so "optimize undo" can restore them. */
    private static final Map<String, String> OPTIMIZE_BACKUP = new LinkedHashMap<>();

    private ClientControlService() {
    }

    public static void initialize() {
        ClientTickEvents.END_CLIENT_TICK.register(ClientControlService::tick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> disconnectAtMs = 0L);
    }

    private static void tick(Minecraft client) {
        if (!REMINDERS.isEmpty() && client.player != null) {
            checkReminders(client);
        }
        if (disconnectAtMs == 0L) {
            return;
        }
        if (client.level == null || client.player == null) {
            disconnectAtMs = 0L;
            return;
        }
        long left = disconnectAtMs - System.currentTimeMillis();
        if (left <= 0L) {
            disconnectAtMs = 0L;
            doDisconnect(client);
            return;
        }
        if (left <= 60_000L && !warned60) {
            warned60 = true;
            ChatUtil.warn(client, "Disconnect in about 60 seconds. Use /askme control disconnect cancel to stop it.");
        }
        if (left <= 10_000L && !warned10) {
            warned10 = true;
            ChatUtil.warn(client, "Disconnect in 10 seconds.");
        }
    }

    private static void checkReminders(Minecraft client) {
        long now = System.currentTimeMillis();
        for (int i = REMINDERS.size() - 1; i >= 0; i--) {
            Reminder r = REMINDERS.get(i);
            if (r.atMs() <= now) {
                REMINDERS.remove(i);
                ChatUtil.warn(client, "Reminder: " + r.text());
                NameHighlighter.playSoundNow(client, "block.note_block.bell", 100);
            }
        }
    }

    /**
     * Reads a time at the START of the text: "10m", "1h30m", "in 5 min", "at 23:30".
     * Returns null if the text does not start with a time.
     */
    private static When parseWhen(String text) {
        String t = text.toLowerCase(Locale.ROOT).trim();
        Matcher clock = CLOCK_TIME.matcher(t);
        if (clock.matches()) {
            int hour = Integer.parseInt(clock.group(1));
            int minute = Integer.parseInt(clock.group(2));
            if (hour > 23 || minute > 59) {
                return null;
            }
            LocalDateTime now = LocalDateTime.now(ZoneId.systemDefault());
            LocalDateTime target = now.toLocalDate().atTime(LocalTime.of(hour, minute));
            if (!target.isAfter(now)) {
                target = target.plusDays(1);
            }
            long ms = java.time.Duration.between(now, target).toMillis();
            return new When(ms, clock.group(3).trim());
        }
        Matcher lead = LEADING_DURATION.matcher(t);
        if (lead.matches()) {
            long ms = parseDuration(lead.group(1));
            return ms > 0 ? new When(ms, lead.group(2).trim()) : null;
        }
        return null;
    }

    private static boolean addReminder(Minecraft client, long delayMs, String text) {
        if (REMINDERS.size() >= 20) {
            ChatUtil.warn(client, "Too many reminders (max 20). Use: control remind cancel all");
            return false;
        }
        String clean = text == null || text.isBlank() ? "Time is up." : text.trim();
        if (clean.length() > 120) {
            clean = clean.substring(0, 120);
        }
        REMINDERS.add(new Reminder(System.currentTimeMillis() + delayMs, clean));
        ChatUtil.info(client, "I will remind you in " + formatDuration(delayMs) + ": " + clean
                + " (reminders are lost when the game closes)");
        return true;
    }

    private static boolean remind(Minecraft client, String[] parts) {
        String rest = parts.length > 1 ? String.join(" ", java.util.Arrays.copyOfRange(parts, 1, parts.length)) : "";
        String lower = rest.toLowerCase(Locale.ROOT).trim();
        if (lower.isEmpty() || lower.equals("list")) {
            if (REMINDERS.isEmpty()) {
                ChatUtil.info(client, "No reminders. Example: control remind 10m drink water  or  control remind at 23:30 sleep");
                return true;
            }
            long now = System.currentTimeMillis();
            StringBuilder out = new StringBuilder("Reminders: ");
            for (int i = 0; i < REMINDERS.size(); i++) {
                Reminder r = REMINDERS.get(i);
                out.append('#').append(i + 1).append(" in ").append(formatDuration(Math.max(0L, r.atMs() - now)))
                        .append(" - ").append(r.text()).append("; ");
            }
            ChatUtil.info(client, out.toString());
            return true;
        }
        if (lower.startsWith("cancel")) {
            String which = lower.substring(6).trim();
            if (which.isEmpty() || which.equals("all")) {
                REMINDERS.clear();
                ChatUtil.info(client, "All reminders cancelled.");
                return true;
            }
            try {
                int index = Integer.parseInt(which.replace("#", "")) - 1;
                if (index >= 0 && index < REMINDERS.size()) {
                    REMINDERS.remove(index);
                    ChatUtil.info(client, "Reminder cancelled.");
                    return true;
                }
            } catch (NumberFormatException ignored) {
                // fall through
            }
            ChatUtil.warn(client, "Usage: control remind cancel <number|all>");
            return false;
        }
        When when = parseWhen(rest);
        if (when == null || when.delayMs() <= 0L) {
            ChatUtil.warn(client, "Usage: control remind <10m|1h30m|at 23:30> <text>");
            return false;
        }
        return addReminder(client, Math.min(when.delayMs(), 48L * 3_600_000L), when.rest());
    }

    /** Text added to the AI system prompt so the AI knows every CONTROL line it can write. */
    public static String protocolHelp() {
        return "where <op> is one of: fov <30-110>, gamma <0-100>, sensitivity <0-200>, hud <on|off>, "
                + "perspective <first|third|front>, disconnect, disconnect <10s|5m|1h> (timed), disconnect cancel, "
                + "bind <keyMapping> <newKey>, option <name> <value> (ANY video/sound/game setting, for example "
                + "option renderDistance 8), options <filter> (list settings), volume <master|music|...> <0-100>, "
                + "optimize (faster game), optimize undo, press <keyMapping> (presses a key, for example to toggle "
                + "a mod feature), keys <filter>, mods <filter>, modconfig <modid> <key> <value>, doctor (check log "
                + "for mod errors), gc, fullscreen, remind <10m|1h30m|at 23:30> <text> (reminder for anything), "
                + "remind list, remind cancel <number|all>, coords (show position), deathpos (last death position), "
                + "playtime (time played this session), waypoint save <name> / waypoint del <name> / waypoint list (saved places), "
                + "goto <x> <y> <z> (arrow guide to a place, clears itself on arrival) / goto clear, "
                + "mentions (who wrote my name). disconnect also accepts a clock time: disconnect at 23:30. ";
    }

    /**
     * Handles simple requests typed by the player with NO API call (saves quota).
     * Returns true when the text was handled here.
     */
    public static boolean tryLocal(Minecraft client, String text) {
        if (client == null || text == null) {
            return false;
        }
        String q = text.toLowerCase(Locale.ROOT).trim();
        if (q.isEmpty() || q.length() > 120) {
            return false;
        }

        // Reminders: "10 minute baad yaad dilana pani peena hai", "remind me in 5 min to check furnace", "at 23:30 remind sleep"
        if (!q.contains("?") && q.matches("^(reminders|show reminders|remind list|reminder list)[.! ]*$")) {
            return executeControl(client, "remind list");
        }
        if (!q.contains("?") && REMIND_WORDS.matcher(q).find()) {
            long ms = 0L;
            Matcher clock = Pattern.compile("\\bat\\s+(\\d{1,2}):(\\d{2})\\b").matcher(q);
            if (clock.find()) {
                When when = parseWhen("at " + clock.group(1) + ":" + clock.group(2));
                ms = when == null ? 0L : when.delayMs();
            } else {
                ms = parseDuration(q);
            }
            if (ms > 0L) {
                String note = q.replaceAll("\\bat\\s+\\d{1,2}:\\d{2}\\b", " ");
                note = DURATION_PART.matcher(note).replaceAll(" ");
                note = REMIND_WORDS.matcher(note).replaceAll(" ");
                note = note.replaceAll("\\b(me|to|after|in|baad|bad|ke|ki|ko|kar|karna|do|dena|dilana|please|plz)\\b", " ")
                        .replaceAll("\\s+", " ").trim();
                return addReminder(client, Math.min(ms, 48L * 3_600_000L), note);
            }
        }

        // A question ("how do I leave...?") is never a command.
        boolean leave = !q.contains("?") && LEAVE_WORDS.matcher(q).find();
        if (leave && q.matches(".*\\b(cancel|stop|ruk|rok|band|abort)\\b.*")) {
            return executeControl(client, "disconnect cancel");
        }
        if (leave) {
            long ms = parseDuration(q);
            if (ms > 0) {
                return executeControl(client, "disconnect " + (ms / 1000L) + "s");
            }
            if (q.matches("^(disconnect|dc|leave|leave server|leave the server|quit server)[.! ]*$")) {
                return executeControl(client, "disconnect");
            }
        }
        Matcher fov = FOV_PATTERN.matcher(q);
        if (fov.find()) {
            return executeControl(client, "fov " + fov.group(1));
        }
        Matcher render = RENDER_PATTERN.matcher(q);
        if (render.find()) {
            return executeControl(client, "option renderDistance " + render.group(1));
        }
        if (q.matches("^(undo optimi[sz]e|optimi[sz]e undo|restore settings)[.! ]*$")) {
            return executeControl(client, "optimize undo");
        }
        if (q.matches("^(optimi[sz]e|optimi[sz]e game|optimi[sz]e kro|optimize kar do|fps boost)[.! ]*$")) {
            return executeControl(client, "optimize");
        }
        if (q.matches("^(doctor|mod check|check mods|mods check|mod problem|mod doctor)[.! ]*$")) {
            return executeControl(client, "doctor");
        }
        return false;
    }

    /** Reads "10 minute", "1h30m", "30 sec" and similar. Returns milliseconds, or 0 if none found. */
    private static long parseDuration(String q) {
        Matcher m = DURATION_PART.matcher(q);
        long total = 0L;
        while (m.find()) {
            long n;
            try {
                n = Long.parseLong(m.group(1));
            } catch (NumberFormatException e) {
                continue;
            }
            String unit = m.group(2);
            if (unit.startsWith("h") || unit.startsWith("g")) {
                total += n * 3_600_000L;
            } else if (unit.startsWith("s")) {
                total += n * 1_000L;
            } else {
                total += n * 60_000L;
            }
        }
        return Math.min(total, 24L * 3_600_000L);
    }

    public static boolean executeControl(Minecraft client, String rawSpec) {
        if (client == null || rawSpec == null || rawSpec.isBlank()) {
            return false;
        }
        String spec = rawSpec.trim();
        String[] parts = spec.split("\\s+");
        String op = parts[0].toLowerCase(Locale.ROOT);

        try {
            switch (op) {
                case "fov" -> {
                    if (parts.length < 2) {
                        ChatUtil.warn(client, "Usage: control fov <30-110>");
                        return false;
                    }
                    int fov = Math.max(30, Math.min(110, Integer.parseInt(parts[1])));
                    client.options.fov().set(fov);
                    client.options.save();
                    ChatUtil.info(client, "FOV updated to " + fov + ".");
                    return true;
                }
                case "gamma", "brightness" -> {
                    if (parts.length < 2) {
                        ChatUtil.warn(client, "Usage: control gamma <0-100>");
                        return false;
                    }
                    double val = Double.parseDouble(parts[1]);
                    if (val > 1.0) {
                        val = val / 100.0;
                    }
                    val = Math.max(0.0, Math.min(1.0, val));
                    client.options.gamma().set(val);
                    client.options.save();
                    ChatUtil.info(client, "Brightness updated to " + Math.round(val * 100) + "%.");
                    return true;
                }
                case "sensitivity" -> {
                    if (parts.length < 2) {
                        ChatUtil.warn(client, "Usage: control sensitivity <0-200>");
                        return false;
                    }
                    double sens = Double.parseDouble(parts[1]);
                    if (sens > 1.0) {
                        sens = sens / 100.0;
                    }
                    sens = Math.max(0.0, Math.min(2.0, sens));
                    client.options.sensitivity().set(sens);
                    client.options.save();
                    ChatUtil.info(client, "Mouse sensitivity updated.");
                    return true;
                }
                case "hud" -> {
                    boolean nextHide;
                    if (parts.length < 2) {
                        nextHide = !client.gui.hud.isHidden();
                    } else {
                        nextHide = parts[1].equalsIgnoreCase("off") || parts[1].equalsIgnoreCase("hide");
                    }
                    setHudHidden(client.gui.hud, nextHide);
                    client.options.save();
                    ChatUtil.info(client, "HUD visibility: " + (nextHide ? "HIDDEN" : "VISIBLE") + ".");
                    return true;
                }
                case "perspective", "view" -> {
                    if (parts.length < 2) {
                        return false;
                    }
                    CameraType type = switch (parts[1].toLowerCase(Locale.ROOT)) {
                        case "1", "first", "fpp" -> CameraType.FIRST_PERSON;
                        case "2", "third", "back", "3rd" -> CameraType.THIRD_PERSON_BACK;
                        case "3", "front", "selfie" -> CameraType.THIRD_PERSON_FRONT;
                        default -> null;
                    };
                    if (type == null) {
                        ChatUtil.warn(client, "Unknown perspective: " + parts[1]);
                        return false;
                    }
                    client.options.setCameraType(type);
                    client.options.save();
                    ChatUtil.info(client, "Camera perspective set to " + type.name() + ".");
                    return true;
                }
                case "disconnect", "leave", "quitserver", "kick" -> {
                    String arg = parts.length > 1 ? String.join(" ", java.util.Arrays.copyOfRange(parts, 1, parts.length)) : "";
                    if (arg.equalsIgnoreCase("cancel") || arg.equalsIgnoreCase("stop")) {
                        boolean had = disconnectAtMs != 0L;
                        disconnectAtMs = 0L;
                        ChatUtil.info(client, had ? "Planned disconnect cancelled." : "No disconnect was planned.");
                        return true;
                    }
                    if (arg.equalsIgnoreCase("status")) {
                        long left = disconnectAtMs - System.currentTimeMillis();
                        ChatUtil.info(client, disconnectAtMs == 0L ? "No disconnect planned."
                                : "Disconnect in " + formatDuration(Math.max(0L, left)) + ".");
                        return true;
                    }
                    if (!arg.isEmpty()) {
                        When when = parseWhen(arg);
                        long ms = when != null ? when.delayMs() : parseDuration(arg.toLowerCase(Locale.ROOT));
                        if (ms == 0L && arg.matches("\\d+")) {
                            ms = Long.parseLong(arg) * 60_000L; // plain number = minutes
                        }
                        if (ms <= 0L) {
                            ChatUtil.warn(client, "Usage: control disconnect [10s|5m|1h|cancel|status]");
                            return false;
                        }
                        if (client.level == null) {
                            ChatUtil.warn(client, "Not connected to a world or server.");
                            return false;
                        }
                        disconnectAtMs = System.currentTimeMillis() + ms;
                        warned60 = ms <= 60_000L;
                        warned10 = ms <= 10_000L;
                        ChatUtil.info(client, "You will be disconnected in " + formatDuration(ms)
                                + ". (control disconnect cancel = stop it)");
                        return true;
                    }
                    return doDisconnect(client);
                }
                case "bind", "keybind", "remap" -> {
                    if (parts.length < 3) {
                        ChatUtil.warn(client, "Usage: control bind <keyMapping> <newKey>");
                        return false;
                    }
                    KeyMapping mapping = findKeyMapping(client.options, parts[1]);
                    InputConstants.Key key = resolveInputKey(parts[2]);
                    if (mapping == null || key == InputConstants.UNKNOWN) {
                        ChatUtil.warn(client, "Invalid keybind or key: " + parts[1] + " -> " + parts[2]);
                        return false;
                    }
                    mapping.setKey(key);
                    KeyMapping.resetMapping();
                    client.options.save();
                    ChatUtil.info(client, "Bound " + mapping.getName() + " to " + key.getName() + ".");
                    return true;
                }
                case "option", "set", "setting" -> {
                    if (parts.length < 3) {
                        ChatUtil.warn(client, "Usage: control option <name> <value>  (list names: control options)");
                        return false;
                    }
                    return setOptionByName(client, parts[1], String.join(" ", java.util.Arrays.copyOfRange(parts, 2, parts.length)));
                }
                case "options", "settings" -> {
                    ChatUtil.info(client, listOptions(client.options, parts.length > 1 ? parts[1] : ""));
                    return true;
                }
                case "volume" -> {
                    if (parts.length < 3) {
                        ChatUtil.warn(client, "Usage: control volume <master|music|record|weather|block|hostile|neutral|player|ambient|voice> <0-100>");
                        return false;
                    }
                    SoundSource source;
                    try {
                        source = SoundSource.valueOf(parts[1].toUpperCase(Locale.ROOT));
                    } catch (IllegalArgumentException unknown) {
                        ChatUtil.warn(client, "Unknown sound group: " + parts[1]);
                        return false;
                    }
                    double v = Math.max(0.0, Math.min(100.0, Double.parseDouble(parts[2].replace("%", "")))) / 100.0;
                    client.options.getSoundSourceOptionInstance(source).set(v);
                    client.options.save();
                    ChatUtil.info(client, source.name().toLowerCase(Locale.ROOT) + " volume set to " + Math.round(v * 100) + "%.");
                    return true;
                }
                case "optimize", "optimise" -> {
                    return parts.length > 1 && parts[1].equalsIgnoreCase("undo")
                            ? optimizeUndo(client) : optimize(client);
                }
                case "press" -> {
                    if (parts.length < 2) {
                        ChatUtil.warn(client, "Usage: control press <keyMapping>");
                        return false;
                    }
                    return pressKey(client, parts[1]);
                }
                case "keys" -> {
                    ChatUtil.info(client, listKeys(client.options, parts.length > 1 ? parts[1] : ""));
                    return true;
                }
                case "mods" -> {
                    ChatUtil.info(client, listMods(parts.length > 1 ? parts[1] : ""));
                    return true;
                }
                case "modconfig" -> {
                    return modConfig(client, parts);
                }
                case "doctor" -> {
                    ModDoctor.run(client, parts.length > 1 && parts[1].equalsIgnoreCase("fix"));
                    return true;
                }
                case "remind", "reminder" -> {
                    return remind(client, parts);
                }
                case "coords", "position", "pos" -> {
                    if (client.player == null) {
                        ChatUtil.warn(client, "You are not in a world.");
                        return false;
                    }
                    ChatUtil.info(client, "Your position: X " + client.player.getBlockX()
                            + "  Y " + client.player.getBlockY() + "  Z " + client.player.getBlockZ());
                    return true;
                }
                case "waypoint", "wp" -> {
                    String sub = parts.length > 1 ? parts[1].toLowerCase(Locale.ROOT) : "list";
                    String arg = parts.length > 2 ? parts[2] : "";
                    String message = switch (sub) {
                        case "save", "set", "add" -> Waypoints.save(client, arg);
                        case "del", "delete", "remove" -> Waypoints.delete(client, arg);
                        default -> Waypoints.list(client);
                    };
                    ChatUtil.info(client, message);
                    return true;
                }
                case "goto", "guide", "navigate" -> {
                    if (parts.length >= 2 && (parts[1].equalsIgnoreCase("clear") || parts[1].equalsIgnoreCase("stop"))) {
                        ChatUtil.info(client, Guide.clear());
                        return true;
                    }
                    if (parts.length >= 4) {
                        try {
                            int x = (int) Math.round(Double.parseDouble(parts[1]));
                            int y = (int) Math.round(Double.parseDouble(parts[2]));
                            int z = (int) Math.round(Double.parseDouble(parts[3]));
                            ChatUtil.info(client, Guide.start(client, "target", x, y, z));
                            return true;
                        } catch (NumberFormatException bad) {
                            ChatUtil.warn(client, "Usage: control goto <x> <y> <z> | goto clear");
                            return false;
                        }
                    }
                    ChatUtil.warn(client, "Usage: control goto <x> <y> <z> | goto clear");
                    return false;
                }
                case "mentions" -> {
                    ChatUtil.info(client, Mentions.text());
                    return true;
                }
                case "deathpos" -> {
                    ChatUtil.info(client, DeathExplainer.lastDeathText());
                    return true;
                }
                case "playtime", "session" -> {
                    ChatUtil.info(client, "Playing time this session: "
                            + formatDuration(System.currentTimeMillis() - SESSION_START_MS));
                    return true;
                }
                case "gc" -> {
                    System.gc();
                    ChatUtil.info(client, "Memory cleanup requested.");
                    return true;
                }
                case "fullscreen" -> {
                    client.getWindow().toggleFullScreen();
                    return true;
                }
                default -> {
                    ChatUtil.warn(client, "Unsupported control operation: " + spec);
                    return false;
                }
            }
        } catch (Exception ex) {
            AskMe.LOGGER.error("[Ask Me] Failed to run client control '{}'", spec, ex);
            ChatUtil.error(client, "Control error: " + ex.getMessage());
            return false;
        }
    }

    private static boolean doDisconnect(Minecraft client) {
        if (client.level != null) {
            client.level.disconnect(Component.literal("Disconnected by Ask Me"));
            ChatUtil.info(client, "Disconnected from current server.");
            return true;
        }
        ChatUtil.warn(client, "Not connected to a world or server.");
        return false;
    }

    private static String formatDuration(long ms) {
        long sec = ms / 1000L;
        if (sec >= 3600) {
            return (sec / 3600) + "h " + ((sec % 3600) / 60) + "m";
        }
        if (sec >= 60) {
            return (sec / 60) + "m " + (sec % 60) + "s";
        }
        return sec + "s";
    }

    // ------------------------------------------------------------------ generic game options

    /** Finds every OptionInstance field of the game Options object (render distance, vsync, particles...). */
    private static Map<String, OptionInstance<?>> optionMap(Options options) {
        Map<String, OptionInstance<?>> map = new TreeMap<>();
        for (Field field : Options.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || !OptionInstance.class.isAssignableFrom(field.getType())) {
                continue;
            }
            try {
                field.setAccessible(true);
                Object value = field.get(options);
                if (value instanceof OptionInstance<?> instance) {
                    map.put(field.getName().toLowerCase(Locale.ROOT), instance);
                }
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // skip this option
            }
        }
        return map;
    }

    private static String listOptions(Options options, String filter) {
        String needle = filter == null ? "" : filter.toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder("Game settings: ");
        int count = 0;
        for (Map.Entry<String, OptionInstance<?>> e : optionMap(options).entrySet()) {
            if (!needle.isEmpty() && !e.getKey().contains(needle)) {
                continue;
            }
            Object v;
            try {
                v = e.getValue().get();
            } catch (RuntimeException ex) {
                continue;
            }
            out.append(e.getKey()).append('=').append(v).append("; ");
            if (++count >= 45) {
                out.append("...(use a filter)");
                break;
            }
        }
        return count == 0 ? "No setting matches '" + filter + "'." : out.toString();
    }

    private static boolean setOptionByName(Minecraft client, String name, String raw) {
        OptionInstance<?> instance = optionMap(client.options).get(name.toLowerCase(Locale.ROOT));
        if (instance == null) {
            ChatUtil.warn(client, "No setting named '" + name + "'. Use: control options " + name.substring(0, Math.min(4, name.length())));
            return false;
        }
        String result = applyOption(instance, raw);
        if (result == null) {
            client.options.save();
            ChatUtil.info(client, name + " is now " + instance.get() + ".");
            return true;
        }
        ChatUtil.warn(client, name + ": " + result);
        return false;
    }

    /** Returns null when applied, otherwise an error text. */
    @SuppressWarnings("unchecked")
    private static String applyOption(OptionInstance<?> instance, String rawValue) {
        String raw = rawValue.trim();
        Object current = instance.get();
        Object next;
        try {
            if (current instanceof Boolean) {
                String r = raw.toLowerCase(Locale.ROOT);
                if (List.of("on", "true", "yes", "1", "enable", "enabled").contains(r)) {
                    next = Boolean.TRUE;
                } else if (List.of("off", "false", "no", "0", "disable", "disabled").contains(r)) {
                    next = Boolean.FALSE;
                } else {
                    return "needs on or off";
                }
            } else if (current instanceof Integer) {
                next = Integer.parseInt(raw.replaceAll("[^0-9-]", ""));
            } else if (current instanceof Double) {
                double d = Double.parseDouble(raw.replace("%", ""));
                if (raw.endsWith("%") || d > 5.0) {
                    d = d / 100.0;
                }
                next = d;
            } else if (current instanceof Enum<?> e) {
                Object match = null;
                for (Object constant : e.getDeclaringClass().getEnumConstants()) {
                    if (((Enum<?>) constant).name().equalsIgnoreCase(raw.replace(' ', '_'))) {
                        match = constant;
                    }
                }
                if (match == null) {
                    return "allowed values: " + java.util.Arrays.toString(e.getDeclaringClass().getEnumConstants());
                }
                next = match;
            } else {
                return "this setting type cannot be changed by text";
            }
            ((OptionInstance<Object>) instance).set(next);
        } catch (NumberFormatException bad) {
            return "I need a number";
        } catch (RuntimeException problem) {
            return "failed (" + problem.getClass().getSimpleName() + ")";
        }
        Object after = instance.get();
        boolean same = after instanceof Double a && next instanceof Double b
                ? Math.abs(a - b) < 0.001 : java.util.Objects.equals(after, next);
        return same ? null : "value not accepted (out of range?), it stays " + after;
    }

    /** Lower settings that cost the most FPS. Missing settings are skipped. */
    private static boolean optimize(Minecraft client) {
        String[][] plan = {
                {"renderdistance", "8"}, {"simulationdistance", "6"}, {"entitydistancescaling", "0.75"},
                {"particles", "minimal"}, {"cloudstatus", "off"}, {"biomeblendradius", "1"},
                {"entityshadows", "off"}, {"ambientocclusion", "off"}, {"mipmaplevels", "0"},
                {"enablevsync", "off"}, {"framerateLimit", "144"}, {"bobview", "off"}
        };
        Map<String, OptionInstance<?>> map = optionMap(client.options);
        int applied = 0;
        for (String[] step : plan) {
            OptionInstance<?> instance = map.get(step[0].toLowerCase(Locale.ROOT));
            if (instance == null) {
                continue;
            }
            String before = instance.get() instanceof Enum<?> e ? e.name() : String.valueOf(instance.get());
            if (applyOption(instance, step[1]) == null) {
                OPTIMIZE_BACKUP.putIfAbsent(step[0].toLowerCase(Locale.ROOT), before);
                applied++;
            }
        }
        client.options.save();
        ChatUtil.info(client, "Optimized " + applied + " settings for more FPS. Use 'control optimize undo' to go back.");
        return applied > 0;
    }

    private static boolean optimizeUndo(Minecraft client) {
        if (OPTIMIZE_BACKUP.isEmpty()) {
            ChatUtil.warn(client, "Nothing to undo.");
            return false;
        }
        Map<String, OptionInstance<?>> map = optionMap(client.options);
        int restored = 0;
        for (Map.Entry<String, String> e : OPTIMIZE_BACKUP.entrySet()) {
            OptionInstance<?> instance = map.get(e.getKey());
            if (instance != null && applyOption(instance, e.getValue()) == null) {
                restored++;
            }
        }
        OPTIMIZE_BACKUP.clear();
        client.options.save();
        ChatUtil.info(client, "Restored " + restored + " settings.");
        return true;
    }

    // ------------------------------------------------------------------ keys and other mods

    private static String listKeys(Options options, String filter) {
        String needle = filter == null ? "" : filter.toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder("Key actions: ");
        int count = 0;
        for (KeyMapping km : options.keyMappings) {
            String name = km.getName();
            if (!needle.isEmpty() && !name.toLowerCase(Locale.ROOT).contains(needle)) {
                continue;
            }
            out.append(name.startsWith("key.") ? name.substring(4) : name).append('=')
                    .append(km.getTranslatedKeyMessage().getString()).append("; ");
            if (++count >= 40) {
                out.append("...(use a filter)");
                break;
            }
        }
        return count == 0 ? "No key action matches '" + filter + "'." : out.toString();
    }

    /** Simulates one press of a key action. Most mods toggle their features this way. */
    private static boolean pressKey(Minecraft client, String query) {
        KeyMapping mapping = findKeyMapping(client.options, query);
        if (mapping == null) {
            ChatUtil.warn(client, "No key action named '" + query + "'. Use: control keys " + query);
            return false;
        }
        try {
            Field click = KeyMapping.class.getDeclaredField("clickCount");
            click.setAccessible(true);
            click.setInt(mapping, click.getInt(mapping) + 1);
            ChatUtil.info(client, "Pressed " + mapping.getName() + ".");
            return true;
        } catch (ReflectiveOperationException | RuntimeException problem) {
            ChatUtil.warn(client, "Could not press " + mapping.getName() + " (" + problem.getClass().getSimpleName() + ").");
            return false;
        }
    }

    private static String listMods(String filter) {
        String needle = filter == null ? "" : filter.toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder("Mods: ");
        int count = 0;
        for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
            String id = mod.getMetadata().getId();
            if (id.startsWith("fabric-") || id.equals("java") || id.equals("minecraft") || id.startsWith("mixinextras")) {
                continue;
            }
            String line = id + " (" + mod.getMetadata().getName() + " " + mod.getMetadata().getVersion().getFriendlyString() + ")";
            if (!needle.isEmpty() && !line.toLowerCase(Locale.ROOT).contains(needle)) {
                continue;
            }
            out.append(line).append("; ");
            if (++count >= 40) {
                out.append("...(use a filter)");
                break;
            }
        }
        return count == 0 ? "No mod matches '" + filter + "'." : out.toString();
    }

    /**
     * Edits a simple setting inside another mod's config file (config/&lt;modid&gt;.json or .properties).
     * Many mods only read the file at startup, so a restart or the mod's own screen may be needed.
     */
    private static boolean modConfig(Minecraft client, String[] parts) {
        if (parts.length < 2) {
            ChatUtil.warn(client, "Usage: control modconfig <modid> [key value]");
            return false;
        }
        String modId = parts[1].toLowerCase(Locale.ROOT);
        Path file = findConfigFile(modId);
        if (file == null) {
            ChatUtil.warn(client, "No .json or .properties config file found for '" + modId + "' in the config folder.");
            return false;
        }
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            boolean json = file.getFileName().toString().endsWith(".json");
            if (parts.length < 4) {
                ChatUtil.info(client, file.getFileName() + ": " + (json ? jsonKeys(content) : propertyKeys(content)));
                return true;
            }
            String key = parts[2];
            String value = String.join(" ", java.util.Arrays.copyOfRange(parts, 3, parts.length));
            String updated = json ? setJsonValue(content, key, value) : setPropertyValue(content, key, value);
            if (updated == null) {
                ChatUtil.warn(client, "Key '" + key + "' not found in " + file.getFileName() + ".");
                return false;
            }
            Files.writeString(file, updated, StandardCharsets.UTF_8);
            ChatUtil.info(client, "Saved " + key + " = " + value + " in " + file.getFileName()
                    + ". If nothing changes, restart the game or use the mod's own settings screen.");
            return true;
        } catch (IOException | RuntimeException problem) {
            ChatUtil.warn(client, "Could not edit the config: " + problem.getClass().getSimpleName());
            return false;
        }
    }

    private static Path findConfigFile(String modId) {
        Path dir = FabricLoader.getInstance().getConfigDir();
        try (var stream = Files.list(dir)) {
            List<Path> all = stream.toList();
            for (String ext : List.of(".json", ".properties")) {
                for (Path p : all) {
                    String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                    if (n.endsWith(ext) && (n.equals(modId + ext) || n.startsWith(modId + "-") || n.startsWith(modId + "_"))) {
                        return p;
                    }
                }
            }
        } catch (IOException ignored) {
            // no config folder
        }
        return null;
    }

    private static String jsonKeys(String content) {
        List<String> keys = new ArrayList<>();
        collectKeys(JsonParser.parseString(content), "", keys);
        return String.join(", ", keys.subList(0, Math.min(keys.size(), 40)));
    }

    private static void collectKeys(JsonElement element, String prefix, List<String> out) {
        if (element.isJsonObject()) {
            for (var e : element.getAsJsonObject().entrySet()) {
                String path = prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey();
                if (e.getValue().isJsonPrimitive()) {
                    out.add(path + "=" + e.getValue().getAsString());
                } else {
                    collectKeys(e.getValue(), path, out);
                }
            }
        }
    }

    private static String setJsonValue(String content, String key, String value) {
        JsonElement root = JsonParser.parseString(content);
        if (!root.isJsonObject()) {
            return null;
        }
        JsonObject holder = findJsonHolder(root.getAsJsonObject(), key.toLowerCase(Locale.ROOT));
        if (holder == null) {
            return null;
        }
        String leaf = key.contains(".") ? key.substring(key.lastIndexOf('.') + 1) : key;
        String realKey = null;
        for (String k : holder.keySet()) {
            if (k.equalsIgnoreCase(leaf)) {
                realKey = k;
            }
        }
        if (realKey == null) {
            return null;
        }
        JsonElement old = holder.get(realKey);
        JsonPrimitive next;
        if (old.isJsonPrimitive() && old.getAsJsonPrimitive().isBoolean()) {
            next = new JsonPrimitive(List.of("on", "true", "yes", "1").contains(value.toLowerCase(Locale.ROOT)));
        } else if (old.isJsonPrimitive() && old.getAsJsonPrimitive().isNumber()) {
            next = new JsonPrimitive(Double.parseDouble(value) % 1 == 0 && !value.contains(".")
                    ? (Number) Long.valueOf(value) : (Number) Double.valueOf(value));
        } else {
            next = new JsonPrimitive(value);
        }
        holder.add(realKey, next);
        return new GsonBuilder().setPrettyPrinting().create().toJson(root);
    }

    /** Walks a dotted path, or searches the whole tree for a leaf name. */
    private static JsonObject findJsonHolder(JsonObject root, String key) {
        if (key.contains(".")) {
            JsonObject current = root;
            String[] path = key.split("\\.");
            for (int i = 0; i < path.length - 1; i++) {
                JsonElement next = null;
                for (String k : current.keySet()) {
                    if (k.equalsIgnoreCase(path[i])) {
                        next = current.get(k);
                    }
                }
                if (next == null || !next.isJsonObject()) {
                    return null;
                }
                current = next.getAsJsonObject();
            }
            return current;
        }
        for (String k : root.keySet()) {
            if (k.equalsIgnoreCase(key) && root.get(k).isJsonPrimitive()) {
                return root;
            }
        }
        for (String k : root.keySet()) {
            if (root.get(k).isJsonObject()) {
                JsonObject found = findJsonHolder(root.get(k).getAsJsonObject(), key);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static String propertyKeys(String content) {
        List<String> keys = new ArrayList<>();
        for (String line : content.split("\\r?\\n")) {
            if (!line.isBlank() && !line.startsWith("#") && line.contains("=")) {
                keys.add(line.trim());
            }
        }
        return String.join(", ", keys.subList(0, Math.min(keys.size(), 40)));
    }

    private static String setPropertyValue(String content, String key, String value) {
        StringBuilder out = new StringBuilder();
        boolean found = false;
        for (String line : content.split("\\r?\\n", -1)) {
            int eq = line.indexOf('=');
            if (!line.startsWith("#") && eq > 0 && line.substring(0, eq).trim().equalsIgnoreCase(key)) {
                out.append(line, 0, eq + 1).append(value).append('\n');
                found = true;
            } else {
                out.append(line).append('\n');
            }
        }
        return found ? out.toString().stripTrailing() + "\n" : null;
    }

    /**
     * MC 26.2 me Hud class ka setter ka naam pata nahi tha, is liye reflection se dhundte hain.
     * Pehle boolean setter, phir boolean field, phir toggle method try hota ha.
     */
    private static void setHudHidden(Object hud, boolean hide) throws Exception {
        Class<?> c = hud.getClass();

        for (var m : c.getDeclaredMethods()) {
            if (m.getParameterCount() == 1 && m.getParameterTypes()[0] == boolean.class
                    && m.getName().toLowerCase(Locale.ROOT).contains("hid")) {
                m.setAccessible(true);
                m.invoke(hud, hide);
                return;
            }
        }

        for (var f : c.getDeclaredFields()) {
            if (f.getType() == boolean.class && !Modifier.isStatic(f.getModifiers())
                    && f.getName().toLowerCase(Locale.ROOT).contains("hid")) {
                f.setAccessible(true);
                f.setBoolean(hud, hide);
                return;
            }
        }

        for (var m : c.getDeclaredMethods()) {
            String n = m.getName().toLowerCase(Locale.ROOT);
            if (m.getParameterCount() == 0 && (n.contains("toggle") || n.contains("flip"))) {
                boolean cur = (boolean) c.getMethod("isHidden").invoke(hud);
                if (cur != hide) {
                    m.setAccessible(true);
                    m.invoke(hud);
                }
                return;
            }
        }

        throw new IllegalStateException("HUD hide method not found in " + c.getName());
    }

    private static KeyMapping findKeyMapping(Options options, String query) {
        String q = query.toLowerCase(Locale.ROOT).trim();
        for (KeyMapping km : options.keyMappings) {
            String name = km.getName().toLowerCase(Locale.ROOT);
            String shortName = name.startsWith("key.") ? name.substring(4) : name;
            if (name.equals(q) || shortName.equals(q) || shortName.endsWith("." + q)) {
                return km;
            }
        }
        return null;
    }

    private static InputConstants.Key resolveInputKey(String rawKey) {
        String k = rawKey.trim().toLowerCase(Locale.ROOT);
        if (!k.startsWith("key.keyboard.") && !k.startsWith("key.mouse.")) {
            if (k.startsWith("mouse")) {
                k = "key.mouse." + k.replace("mouse", "").replace(".", "");
            } else {
                k = "key.keyboard." + k;
            }
        }
        try {
            return InputConstants.getKey(k);
        } catch (Exception ignored) {
            return InputConstants.UNKNOWN;
        }
    }
}