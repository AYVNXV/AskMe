package com.ayyan.askme.scanner;

import com.ayyan.askme.AskMe;
import com.ayyan.askme.config.AskMeConfig;
import com.ayyan.askme.util.ChatUtil;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.CommandNode;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ServerCommandScanner {

    private static final ScheduledExecutorService SCHEDULER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "AskMe-Scanner");
        t.setDaemon(true);
        return t;
    });

    private static boolean capturingHelp;
    private static long captureStartedAtMs;
    private static String captureServerKey = "";
    private static final List<String> capturedLines = new ArrayList<>();
    private static final List<String> allHelpLines = new ArrayList<>();
    private static final Pattern PAGE_INFO = Pattern.compile("(?i)(?:page|\\()\\s*(\\d+)\\s*(?:/|of)\\s*(\\d+)");
    private static final Pattern SLASH_TOKEN = Pattern.compile("(?<![A-Za-z0-9_/])/[A-Za-z][A-Za-z0-9_-]{1,31}");
    private static final int MAX_HELP_PAGES = 5;
    private static int nextHelpPage;
    private static int totalHelpPages;

    private ServerCommandScanner() {
    }

    public static void initialize() {
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            if (AskMeConfig.get().autoScanOnJoin) {
                SCHEDULER.schedule(() -> client.execute(() -> safely(() -> triggerScan(client, false))), 2, TimeUnit.SECONDS);
            }
        });

        // While the /help answer is being read, it is captured and hidden, so the chat is not filled with it.
        ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) ->
                overlay || message == null || !captureAndHide(message.getString()));
        ClientReceiveMessageEvents.ALLOW_CHAT.register((message, signed, sender, params, ts) ->
                message == null || sender != null || !captureAndHide(message.getString()));
    }

    /** Returns true when the line belongs to the /help answer (it is stored and must be hidden). */
    private static boolean captureAndHide(String text) {
        try {
            return captureAndHideUnsafe(text);
        } catch (Throwable problem) {
            capturingHelp = false;
            return false;
        }
    }

    private static void safely(Runnable task) {
        try {
            task.run();
        } catch (Throwable problem) {
            capturingHelp = false;
            AskMe.LOGGER.warn("[Ask Me] Server scan failed: {}", problem.toString());
        }
    }

    private static boolean captureAndHideUnsafe(String text) {
        if (!capturingHelp || text == null || text.isBlank() || text.startsWith("<")) {
            return false;
        }
        boolean helpLike = SLASH_TOKEN.matcher(text).find() || PAGE_INFO.matcher(text).find()
                || text.toLowerCase(Locale.ROOT).contains("help");
        if (!helpLike) {
            return false;
        }
        onIncomingMessage(text);
        return true;
    }

    public static String getCurrentServerKey(Minecraft client) {
        if (client == null) {
            return "unknown";
        }
        if (client.hasSingleplayerServer()) {
            return "singleplayer";
        }
        ServerData data = client.getCurrentServer();
        if (data != null && data.ip != null && !data.ip.isBlank()) {
            return data.ip.trim().toLowerCase(Locale.ROOT);
        }
        return client.level != null ? "remote_server" : "offline";
    }

    public static void triggerScan(Minecraft client, boolean force) {
        if (client == null || client.player == null || client.player.connection == null) {
            return;
        }
        String serverKey = getCurrentServerKey(client);
        AskMeConfig config = AskMeConfig.get();
        AskMeConfig.ServerCommandCache existing = config.getServerScan(serverKey);
        long now = System.currentTimeMillis();
        if (!force && existing != null && (now - existing.scannedAtMs) < 300_000L && !existing.commands.isEmpty()) {
            return;
        }

        Set<String> commands = new LinkedHashSet<>();
        try {
            CommandDispatcher<?> disp = client.player.connection.getCommands();
            if (disp != null && disp.getRoot() != null) {
                for (CommandNode<?> child : disp.getRoot().getChildren()) {
                    String name = child.getName();
                    if (name != null && !name.isBlank()) {
                        commands.add("/" + name.toLowerCase(Locale.ROOT));
                    }
                }
            }
        } catch (Throwable ex) {
            AskMe.LOGGER.debug("[Ask Me] Dispatcher scan fallback: {}", ex.getMessage());
        }

        config.saveServerScan(serverKey, new ArrayList<>(commands),
                existing != null ? existing.helpLines : List.of());

        capturingHelp = true;
        captureStartedAtMs = now;
        captureServerKey = serverKey;
        capturedLines.clear();
        allHelpLines.clear();
        nextHelpPage = 2;
        totalHelpPages = 1;
        client.player.connection.sendCommand("help");

        SCHEDULER.schedule(() -> client.execute(() -> safely(ServerCommandScanner::finalizeHelpCapture)), 1800, TimeUnit.MILLISECONDS);
    }

    private static void onIncomingMessage(String text) {
        if (!capturingHelp || text == null) {
            return;
        }
        if (System.currentTimeMillis() - captureStartedAtMs > 4000L) {
            finalizeHelpCapture();
            return;
        }
        String clean = text.replaceAll("\u00a7.", "").trim();
        if (clean.isBlank() || clean.contains("[Ask Me]")) {
            return;
        }
        if (capturedLines.size() < 80) {
            capturedLines.add(clean);
        }
    }

    private static synchronized void finalizeHelpCapture() {
        if (!capturingHelp) {
            return;
        }
        capturingHelp = false;
        for (String line : capturedLines) {
            Matcher page = PAGE_INFO.matcher(line);
            if (page.find()) {
                try {
                    int total = Integer.parseInt(page.group(2));
                    if (total > totalHelpPages && total < 100) {
                        totalHelpPages = total;
                    }
                } catch (NumberFormatException ignored) {
                    // not a page counter
                }
            }
        }
        allHelpLines.addAll(capturedLines);
        capturedLines.clear();

        // Many servers split /help into pages: read the next page too (max 5 pages, one per 2 seconds).
        Minecraft client = Minecraft.getInstance();
        if (nextHelpPage <= Math.min(totalHelpPages, MAX_HELP_PAGES) && client.player != null) {
            int page = nextHelpPage++;
            capturingHelp = true;
            captureStartedAtMs = System.currentTimeMillis();
            client.player.connection.sendCommand("help " + page);
            SCHEDULER.schedule(() -> client.execute(() -> safely(ServerCommandScanner::finalizeHelpCapture)),
                    1800, TimeUnit.MILLISECONDS);
            return;
        }

        AskMeConfig config = AskMeConfig.get();
        AskMeConfig.ServerCommandCache prev = config.getServerScan(captureServerKey);
        List<String> cmds = prev != null ? new ArrayList<>(prev.commands) : new ArrayList<>();
        List<String> helpLines = new ArrayList<>();
        for (String line : allHelpLines) {
            if (helpLines.size() < 80) {
                helpLines.add(line.length() > 140 ? line.substring(0, 137) + "..." : line);
            }
            for (String token : line.split("[\\s,]+")) {
                String t = token.trim();
                if (t.startsWith("/") && t.length() > 1 && t.length() <= 32) {
                    String cmd = t.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_/-]", "");
                    if (cmd.length() > 1 && !cmds.contains(cmd)) {
                        cmds.add(cmd);
                    }
                }
            }
        }
        config.saveServerScan(captureServerKey, cmds, helpLines);
        allHelpLines.clear();
        if (client.player != null) {
            ChatUtil.subtle(client, "Saved " + cmds.size() + " commands of this server. Use /askme cmds to see them.");
        }
    }

    private static final int LIST_PAGE_SIZE = 40;

    /** Full saved command list of the current server, in pages. A filter keeps only matching commands. */
    public static String describe(Minecraft client, String filter, int page) {
        AskMeConfig.ServerCommandCache cache = AskMeConfig.get().getServerScan(getCurrentServerKey(client));
        if (cache == null || cache.commands.isEmpty()) {
            return "No commands saved for this server yet. Run /askme scan first.";
        }
        String needle = filter == null ? "" : filter.toLowerCase(Locale.ROOT).trim();
        List<String> matches = new ArrayList<>();
        for (String cmd : cache.commands.stream().sorted().toList()) {
            if (needle.isEmpty() || cmd.contains(needle)) {
                matches.add(cmd);
            }
        }
        if (matches.isEmpty()) {
            return "No saved command matches '" + needle + "'. Saved commands: " + cache.commands.size() + ".";
        }
        int pages = (matches.size() + LIST_PAGE_SIZE - 1) / LIST_PAGE_SIZE;
        int p = Math.max(1, Math.min(page, pages));
        int from = (p - 1) * LIST_PAGE_SIZE;
        StringBuilder out = new StringBuilder();
        out.append(matches.size()).append(" commands").append(needle.isEmpty() ? "" : " matching '" + needle + "'")
                .append(" (page ").append(p).append("/").append(pages).append("): ")
                .append(String.join(" ", matches.subList(from, Math.min(matches.size(), from + LIST_PAGE_SIZE))));
        if (!needle.isEmpty()) {
            int shown = 0;
            for (String line : cache.helpLines) {
                if (line.toLowerCase(Locale.ROOT).contains(needle) && shown++ < 3) {
                    out.append("\n").append(line);
                }
            }
        }
        if (pages > 1) {
            out.append("\nNext page: /askme cmds ").append(needle).append(needle.isEmpty() ? "" : " ").append(p + 1);
        }
        return out.toString();
    }

    /** Answers "which commands does this server have" without the API. Returns true when handled. */
    public static boolean tryLocalList(Minecraft client, String text) {
        if (text == null) {
            return false;
        }
        String q = text.toLowerCase(Locale.ROOT);
        boolean about = q.contains("command") || q.contains("cmd");
        boolean wantsList = q.matches(".*\\b(list|all|show|which|what|kon|konsi|kaun|kitni|kitne|saari|sari|"
                + "dikhao|dikha|bata|bta|batao|available|mojood)\\b.*");
        if (!about || !wantsList || q.length() > 80) {
            return false;
        }
        ChatUtil.info(client, describe(client, "", 1));
        return true;
    }

    public static String buildContextSummary(Minecraft client) {
        String key = getCurrentServerKey(client);
        AskMeConfig.ServerCommandCache cache = AskMeConfig.get().getServerScan(key);
        if (cache == null || (cache.commands.isEmpty() && cache.helpLines.isEmpty())) {
            return "";
        }
        String cmdSample = String.join(", ", cache.commands.stream().limit(120).toList());
        String helpSample = String.join(" | ", cache.helpLines.stream().limit(15).toList());
        return "\nServer (" + key + ") available commands: " + cmdSample
                + (helpSample.isBlank() ? "" : "\nServer /help summary: " + helpSample);
    }
}