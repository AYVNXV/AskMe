package com.ayyan.askme.command;

import com.ayyan.askme.AskMe;
import com.ayyan.askme.chat.ChatLog;
import com.ayyan.askme.config.AskMeConfig;
import com.ayyan.askme.control.ClientControlService;
import com.ayyan.askme.scanner.ServerCommandScanner;
import com.ayyan.askme.gemini.GeminiService;
import com.ayyan.askme.util.ChatUtil;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.CommandNode;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class AiCommands {

    public enum RewireResult {
        OK,
        RESERVED,
        FAILED
    }

    private static final Set<String> RESERVED_ROOTS = Set.of(
            "askme", "help", "say", "me", "msg", "tell", "w", "teammsg", "tm",
            "trigger", "gamemode", "tp", "give", "kill", "effect", "summon",
            "time", "weather", "difficulty", "gamerule", "seed", "list", "stop",
            "kick", "ban", "pardon", "op", "deop", "whitelist", "save-all", "save-on", "save-off"
    );

    private static final List<String> ALLOWED_ACTION_PREFIXES = List.of(
            "/msg ", "/tell ", "/w ", "/r ", "/reply ", "/party ", "/p ", "/guild ", "/g ",
            "/spawn", "/hub", "/lobby", "/warp ", "/home", "/sethome", "/tpa ", "/tpaccept", "/tpdeny",
            "/balance", "/bal", "/pay ", "/shop", "/ah", "/kit ", "/kits", "/nick ", "/afk", "/rules", "/ping"
    );

    private static volatile CommandDispatcher<FabricClientCommandSource> registeredDispatcher;

    private AiCommands() {
    }

    public static void initialize() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            registeredDispatcher = dispatcher;
            String keyword = normalizeKeyword(AskMeConfig.get().commandKeyword);
            if (keyword.isEmpty()) {
                keyword = "ask";
            }
            dispatcher.register(buildAskTree(keyword));
        });
    }

    public static String normalizeKeyword(String raw) {
        if (raw == null) {
            return "";
        }
        String cleaned = raw.trim().toLowerCase(Locale.ROOT);
        if (cleaned.startsWith("/")) {
            cleaned = cleaned.substring(1).trim();
        }
        if (cleaned.isEmpty() || cleaned.length() > 32) {
            return "";
        }
        if (!cleaned.matches("[a-z0-9_-]+")) {
            return "";
        }
        return cleaned;
    }

    public static RewireResult rewirePrefix(String oldKeyword, String newKeyword) {
        String normalizedNew = normalizeKeyword(newKeyword);
        if (normalizedNew.isEmpty()) {
            return RewireResult.FAILED;
        }
        if (RESERVED_ROOTS.contains(normalizedNew)) {
            return RewireResult.RESERVED;
        }
        CommandDispatcher<FabricClientCommandSource> dispatcher = registeredDispatcher;
        if (dispatcher == null) {
            return RewireResult.FAILED;
        }
        String normalizedOld = normalizeKeyword(oldKeyword);
        if (normalizedOld.isEmpty()) {
            normalizedOld = "ask";
        }
        if (normalizedOld.equals(normalizedNew)) {
            return RewireResult.OK;
        }
        CommandNode<FabricClientCommandSource> existingNew = dispatcher.getRoot().getChild(normalizedNew);
        if (existingNew != null) {
            return RewireResult.RESERVED;
        }
        try {
            removeRootChild(dispatcher, normalizedOld);
            dispatcher.register(buildAskTree(normalizedNew));
            return RewireResult.OK;
        } catch (ReflectiveOperationException ex) {
            AskMe.LOGGER.warn("[Ask Me] Dynamic command rewire failed: {}", ex.getMessage());
            return RewireResult.FAILED;
        }
    }

    @SuppressWarnings("unchecked")
    private static void removeRootChild(CommandDispatcher<FabricClientCommandSource> dispatcher, String name)
            throws ReflectiveOperationException {
        CommandNode<FabricClientCommandSource> root = dispatcher.getRoot();
        Field childrenField = CommandNode.class.getDeclaredField("children");
        childrenField.setAccessible(true);
        Map<String, CommandNode<FabricClientCommandSource>> children =
                (Map<String, CommandNode<FabricClientCommandSource>>) childrenField.get(root);
        children.remove(name);

        Field literalsField = CommandNode.class.getDeclaredField("literals");
        literalsField.setAccessible(true);
        Map<String, ?> literals = (Map<String, ?>) literalsField.get(root);
        literals.remove(name);
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> buildAskTree(String literal) {
        return ClientCommands.literal(literal)
                .then(ClientCommands.argument("prompt", StringArgumentType.greedyString())
                        .executes(AiCommands::executeAsk));
    }

    private static int executeAsk(CommandContext<FabricClientCommandSource> context) {
        String prompt = context.getArgument("prompt", String.class);
        runAsk(context.getSource().getClient(), prompt);
        return 1;
    }

    public static void runAsk(Minecraft client, String prompt) {
        if (client == null) {
            return;
        }
        String cleaned = prompt == null ? "" : prompt.trim();
        if (cleaned.isEmpty()) {
            ChatUtil.warn(client, "Please enter a question or control request.");
            return;
        }

        ChatLog.add(ChatLog.Kind.USER, cleaned);

        // Simple requests ("fov 30", "disconnect", "10 minute baad disconnect", "optimize") run locally:
        // instant, and no API quota is used.
        if (ClientControlService.tryLocal(client, cleaned)) {
            return;
        }
        // "Which commands does this server have?" is answered from the saved scan (no API request).
        if (ServerCommandScanner.tryLocalList(client, cleaned)) {
            return;
        }
        ChatUtil.hud(client, Component.translatable("askme.chat.thinking"));

        final boolean allowActions = AskMeConfig.get().allowActions;
        final boolean allowControls = AskMeConfig.get().allowClientControls;

        GeminiService.ask(cleaned, reply -> {
            if (!reply.ok()) {
                String msg = reply.errorMessage();
                ChatLog.add(ChatLog.Kind.SYSTEM, "Error: " + msg);
                ChatUtil.error(client, msg);
                return;
            }
            handleModelOutput(client, reply.text(), allowActions, allowControls);
        });
    }

    private static void handleModelOutput(Minecraft client, String rawText, boolean allowActions,
                                          boolean allowControls) {
        if (rawText == null || rawText.isBlank()) {
            return;
        }
        StringBuilder visible = new StringBuilder();
        for (String line : rawText.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String upper = trimmed.toUpperCase(Locale.ROOT);
            if (upper.startsWith("ACTION::")) {
                if (allowActions) {
                    String cmd = trimmed.substring("ACTION::".length()).trim();
                    runPermittedAction(client, cmd);
                } else {
                    ChatUtil.warn(client, "Blocked ACTION directive because Action Execution is disabled.");
                }
                continue;
            }
            if (upper.startsWith("CONTROL::")) {
                if (allowControls) {
                    String spec = trimmed.substring("CONTROL::".length()).trim();
                    ClientControlService.executeControl(client, spec);
                } else {
                    ChatUtil.warn(client, "Blocked CONTROL directive because Client Controls are disabled.");
                }
                continue;
            }
            if (visible.length() > 0) {
                visible.append('\n');
            }
            visible.append(trimmed);
        }

        String text = visible.toString().trim();
        if (!text.isEmpty()) {
            ChatLog.add(ChatLog.Kind.AI, text);
            ChatUtil.ai(client, text);
        }
    }

    /** Commands the AI may never run, even when Action Execution is on. */
    private static final Set<String> BLOCKED_ROOTS = Set.of(
            "op", "deop", "stop", "ban", "ban-ip", "pardon", "pardon-ip", "kick", "whitelist", "save-off",
            "save-on", "save-all", "reload", "setidletimeout", "publish", "execute", "function", "rcon",
            "restart", "reboot", "shutdown", "lp", "luckperms", "pex", "perm", "permissions");

    private static void runPermittedAction(Minecraft client, String command) {
        if (client.player == null || client.player.connection == null) {
            return;
        }
        String normalized = command.startsWith("/") ? command : "/" + command;
        if (normalized.length() > 256) {
            return;
        }
        String root = normalized.substring(1).split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        if (root.startsWith("minecraft:")) {
            root = root.substring("minecraft:".length());
        }
        if (root.isEmpty() || BLOCKED_ROOTS.contains(root)) {
            ChatUtil.warn(client, "Blocked risky action command: /" + root);
            return;
        }

        boolean permitted = false;
        // 1) commands saved from the server scan (tree + /help)
        AskMeConfig.ServerCommandCache cache = AskMeConfig.get()
                .getServerScan(ServerCommandScanner.getCurrentServerKey(client));
        if (cache != null && cache.commands.contains("/" + root)) {
            permitted = true;
        }
        // 2) commands of other client mods
        CommandDispatcher<FabricClientCommandSource> dispatcher = registeredDispatcher;
        if (!permitted && dispatcher != null && dispatcher.getRoot().getChild(root) != null) {
            permitted = true;
        }
        // 3) common server commands (proxy commands such as /lobby are often not in the scan)
        if (!permitted) {
            String lower = normalized.toLowerCase(Locale.ROOT) + " ";
            for (String allowed : ALLOWED_ACTION_PREFIXES) {
                if (lower.startsWith(allowed.toLowerCase(Locale.ROOT))) {
                    permitted = true;
                    break;
                }
            }
        }
        if (!permitted) {
            ChatUtil.warn(client, "/" + root + " is not in the saved command list of this server. "
                    + "Run /askme scan first, then try again.");
            return;
        }
        client.player.connection.sendCommand(normalized.substring(1));
        ChatUtil.info(client, "Executed action: " + normalized);
    }
}
