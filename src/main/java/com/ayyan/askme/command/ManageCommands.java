package com.ayyan.askme.command;

import com.ayyan.askme.AskMe;
import com.ayyan.askme.config.AskMeConfig;
import com.ayyan.askme.config.KeyMasker;
import com.ayyan.askme.control.ClientControlService;
import com.ayyan.askme.feature.GameAlerts;
import com.ayyan.askme.feature.Guide;
import com.ayyan.askme.feature.Mentions;
import com.ayyan.askme.feature.Waypoints;
import com.ayyan.askme.chat.NameHighlighter;
import com.ayyan.askme.gui.BackgroundHelper;
import com.ayyan.askme.limit.RequestGuard;
import com.ayyan.askme.scanner.ServerCommandScanner;
import com.ayyan.askme.util.ChatUtil;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;
import java.util.function.Function;

public final class ManageCommands {

    private static final int TEXT_PREVIEW_LIMIT = 80;

    private ManageCommands() {
    }

    public static void initialize() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(buildTree()));
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> buildTree() {
        return ClientCommands.literal("askme")
                .then(ClientCommands.literal("help")
                        .executes(context -> {
                            sendHelp(context.getSource().getClient());
                            return 1;
                        }))
                .then(ClientCommands.literal("apikey")
                        .then(ClientCommands.argument("key", StringArgumentType.greedyString())
                                .executes(ManageCommands::saveApiKey)))
                .then(ClientCommands.literal("prefix")
                        .then(ClientCommands.argument("new_prefix", StringArgumentType.word())
                                .executes(ManageCommands::setPrefix)))
                .then(ClientCommands.literal("language")
                        .then(ClientCommands.argument("lang", StringArgumentType.greedyString())
                                .suggests((context, suggestions) ->
                                        SharedSuggestionProvider.suggest(AskMeConfig.SUPPORTED_LANGUAGES, suggestions))
                                .executes(context -> {
                                    String lang = context.getArgument("lang", String.class).trim();
                                    if (lang.isEmpty()) {
                                        return 1;
                                    }
                                    AskMeConfig config = AskMeConfig.get();
                                    config.setLanguage(lang); // also forgets old turns and cached answers
                                    ChatUtil.info(context.getSource().getClient(), "Language set to: " + lang);
                                    return 1;
                                })))
                .then(ClientCommands.literal("control")
                        .then(ClientCommands.argument("spec", StringArgumentType.greedyString())
                                .executes(context -> {
                                    String spec = context.getArgument("spec", String.class);
                                    ClientControlService.executeControl(context.getSource().getClient(), spec);
                                    return 1;
                                })))
                .then(ClientCommands.literal("scan")
                        .executes(context -> {
                            ServerCommandScanner.triggerScan(context.getSource().getClient(), true);
                            ChatUtil.info(context.getSource().getClient(), "Scanning server commands and /help...");
                            return 1;
                        }))
                .then(ClientCommands.literal("cmds")
                        .executes(context -> {
                            Minecraft client = context.getSource().getClient();
                            ChatUtil.info(client, ServerCommandScanner.describe(client, "", 1));
                            return 1;
                        })
                        .then(ClientCommands.argument("filter_or_page", StringArgumentType.greedyString())
                                .executes(context -> {
                                    Minecraft client = context.getSource().getClient();
                                    String arg = context.getArgument("filter_or_page", String.class).trim();
                                    int page = 1;
                                    String filter = arg;
                                    java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(.*?)\\s*(\\d+)$").matcher(arg);
                                    if (m.matches()) {
                                        filter = m.group(1);
                                        page = Integer.parseInt(m.group(2));
                                    }
                                    ChatUtil.info(client, ServerCommandScanner.describe(client, filter, page));
                                    return 1;
                                })))
                .then(toggleNode("explain", c -> c.autoExplain, (c, v) -> c.autoExplain = v,
                        "Auto explain (death / problems)"))
                .then(ClientCommands.literal("remind")
                        .executes(context -> {
                            ClientControlService.executeControl(context.getSource().getClient(), "remind list");
                            return 1;
                        })
                        .then(ClientCommands.argument("what", StringArgumentType.greedyString())
                                .executes(context -> {
                                    ClientControlService.executeControl(context.getSource().getClient(),
                                            "remind " + context.getArgument("what", String.class));
                                    return 1;
                                })))
                .then(toggleNode("onlyquestions", c -> c.replyOnlyQuestions, (c, v) -> c.replyOnlyQuestions = v,
                        "Reply only to questions"))
                .then(toggleNode("modwatch", c -> c.modWatch, (c, v) -> c.modWatch = v,
                        "Mod error watch"))
                .then(toggleNode("highlight", c -> c.nameHighlight, (c, v) -> c.nameHighlight = v,
                        "Name highlight"))
                .then(ClientCommands.literal("namestyle")
                        .then(ClientCommands.literal("color")
                                .then(ClientCommands.argument("hex", StringArgumentType.word())
                                        .suggests((context, suggestions) ->
                                                SharedSuggestionProvider.suggest(AskMeConfig.COLOR_PRESETS, suggestions))
                                        .executes(context -> {
                                            Minecraft client = context.getSource().getClient();
                                            String hex = context.getArgument("hex", String.class);
                                            if (!hex.startsWith("#")) {
                                                hex = "#" + hex;
                                            }
                                            if (!hex.matches("#[0-9A-Fa-f]{6}")) {
                                                ChatUtil.error(client, "Colour must look like #FFD54F.");
                                                return 1;
                                            }
                                            AskMeConfig config = AskMeConfig.get();
                                            config.highlightColor = hex.toUpperCase();
                                            config.save();
                                            ChatUtil.info(client, "Name colour set to " + config.highlightColor + ".");
                                            return 1;
                                        })))
                        .then(toggleNode("bold", c -> c.highlightBold, (c, v) -> c.highlightBold = v, "Name bold"))
                        .then(toggleNode("italic", c -> c.highlightItalic, (c, v) -> c.highlightItalic = v, "Name italic"))
                        .then(toggleNode("underline", c -> c.highlightUnderline, (c, v) -> c.highlightUnderline = v,
                                "Name underline")))
                .then(ClientCommands.literal("sound")
                        .then(ClientCommands.argument("id", StringArgumentType.greedyString())
                                .suggests((context, suggestions) ->
                                        SharedSuggestionProvider.suggest(AskMeConfig.SOUND_PRESETS, suggestions))
                                .executes(context -> {
                                    Minecraft client = context.getSource().getClient();
                                    String id = context.getArgument("id", String.class).trim();
                                    AskMeConfig config = AskMeConfig.get();
                                    if (id.equalsIgnoreCase("test")) {
                                        NameHighlighter.playSoundNow(client, config.nameSound, config.nameSoundVolume);
                                        return 1;
                                    }
                                    config.nameSound = id.equalsIgnoreCase("off") ? "off" : id.toLowerCase(Locale.ROOT);
                                    config.save();
                                    NameHighlighter.playSoundNow(client, config.nameSound, config.nameSoundVolume);
                                    ChatUtil.info(client, "Name sound: " + config.nameSound
                                            + " (any game sound id works, 'off' = silent, 'test' = play it).");
                                    return 1;
                                })))
                .then(ClientCommands.literal("limit")
                        .then(ClientCommands.argument("per_minute", IntegerArgumentType.integer(1, 60))
                                .executes(context -> {
                                    AskMeConfig config = AskMeConfig.get();
                                    config.maxRequestsPerMinute = IntegerArgumentType.getInteger(context, "per_minute");
                                    config.save();
                                    ChatUtil.info(context.getSource().getClient(),
                                            "Request limit: " + config.maxRequestsPerMinute + " per minute.");
                                    return 1;
                                })))
                .then(ClientCommands.literal("usage")
                        .executes(context -> {
                            AskMeConfig config = AskMeConfig.get();
                            ChatUtil.info(context.getSource().getClient(), "Requests in the last minute: "
                                    + RequestGuard.usedInLastMinute() + "/" + config.maxRequestsPerMinute
                                    + ", total this session: " + RequestGuard.totalSent() + ".");
                            return 1;
                        }))
                .then(ClientCommands.literal("doctor")
                        .executes(context -> {
                            ClientControlService.executeControl(context.getSource().getClient(), "doctor");
                            return 1;
                        })
                        .then(ClientCommands.literal("fix")
                                .executes(context -> {
                                    ClientControlService.executeControl(context.getSource().getClient(), "doctor fix");
                                    return 1;
                                })))
                .then(ClientCommands.literal("optimize")
                        .executes(context -> {
                            ClientControlService.executeControl(context.getSource().getClient(), "optimize");
                            return 1;
                        })
                        .then(ClientCommands.literal("undo")
                                .executes(context -> {
                                    ClientControlService.executeControl(context.getSource().getClient(), "optimize undo");
                                    return 1;
                                })))
                .then(ClientCommands.literal("background")
                        .then(ClientCommands.literal("default")
                                .executes(context -> {
                                    BackgroundHelper.useDefault();
                                    ChatUtil.info(context.getSource().getClient(), "Settings background: default picture.");
                                    return 1;
                                }))
                        .then(ClientCommands.literal("dim")
                                .then(ClientCommands.argument("percent", IntegerArgumentType.integer(0, 90))
                                        .executes(context -> {
                                            AskMeConfig config = AskMeConfig.get();
                                            config.backgroundDim = IntegerArgumentType.getInteger(context, "percent");
                                            config.save();
                                            BackgroundHelper.invalidate();
                                            ChatUtil.info(context.getSource().getClient(),
                                                    "Background darkness: " + config.backgroundDim + "%.");
                                            return 1;
                                        })))
                        .then(ClientCommands.argument("path", StringArgumentType.greedyString())
                                .executes(context -> {
                                    Minecraft client = context.getSource().getClient();
                                    String raw = context.getArgument("path", String.class).trim();
                                    if (raw.length() > 1 && raw.startsWith("\"") && raw.endsWith("\"")) {
                                        raw = raw.substring(1, raw.length() - 1);
                                    }
                                    try {
                                        java.nio.file.Path file = java.nio.file.Path.of(raw);
                                        if (!java.nio.file.Files.isRegularFile(file)) {
                                            ChatUtil.error(client, "File not found: " + raw);
                                            return 1;
                                        }
                                        BackgroundHelper.setCustomFrom(file);
                                        ChatUtil.info(client, "Custom settings background saved. Open the settings screen to see it.");
                                    } catch (java.io.IOException | RuntimeException problem) {
                                        ChatUtil.error(client, String.valueOf(problem.getMessage()));
                                    }
                                    return 1;
                                })))
                .then(ClientCommands.literal("systemprompt")
                        .then(ClientCommands.argument("text", StringArgumentType.greedyString())
                                .executes(ManageCommands::setSystemPrompt)))
                .then(ClientCommands.literal("addnickname")
                        .then(ClientCommands.argument("name", StringArgumentType.greedyString())
                                .executes(ManageCommands::setNickname)))
                .then(toggleNode("action", c -> c.allowActions, (c, v) -> c.allowActions = v,
                        "Action Execution"))
                .then(toggleNode("controls", c -> c.allowClientControls, (c, v) -> c.allowClientControls = v,
                        "Client Controls"))
                .then(toggleNode("autoscan", c -> c.autoScanOnJoin, (c, v) -> c.autoScanOnJoin = v,
                        "Auto Join Scan"))
                .then(toggleNode("cache", c -> c.enableCache, (c, v) -> c.enableCache = v,
                        "Response Cache"))
                .then(toggleNode("tagreply", c -> c.autoReply, (c, v) -> c.autoReply = v,
                        "Tag Reply"))
                .then(toggleNode("savage", c -> c.savageMode, (c, v) -> c.savageMode = v,
                        "Savage Mode"))
                .then(toggleNode("publicreply", c -> c.publicReply, (c, v) -> c.publicReply = v,
                        "Public Reply"))
                .then(ClientCommands.literal("cooldown")
                        .then(ClientCommands.argument("seconds", IntegerArgumentType.integer(3, 600))
                                .executes(context -> {
                                    int seconds = IntegerArgumentType.getInteger(context, "seconds");
                                    AskMeConfig config = AskMeConfig.get();
                                    config.replyCooldownSeconds = seconds;
                                    config.save();
                                    ChatUtil.info(context.getSource().getClient(),
                                            "Tag reply cooldown set to " + seconds + " seconds.");
                                    return 1;
                                })))
                .then(ClientCommands.literal("reset")
                        .executes(context -> {
                            AskMeConfig config = AskMeConfig.get();
                            String previous = config.commandKeyword;
                            config.reset();
                            AiCommands.rewirePrefix(previous, "ask");
                            ChatUtil.info(context.getSource().getClient(), "Ask Me settings reset to defaults.");
                            return 1;
                        }))
                .then(ClientCommands.literal("model")
                        .then(ClientCommands.argument("model", StringArgumentType.word())
                                .suggests((context, suggestions) ->
                                        SharedSuggestionProvider.suggest(AskMeConfig.ALLOWED_MODELS, suggestions))
                                .executes(ManageCommands::setModel)))
                .then(ClientCommands.literal("clearhistory")
                        .executes(context -> {
                            AskMeConfig.get().clearConversationHistory();
                            ChatUtil.info(context.getSource().getClient(), "Conversation history cleared.");
                            return 1;
                        }))
                .then(ClientCommands.literal("deathpos")
                        .executes(context -> {
                            ChatUtil.info(context.getSource().getClient(),
                                    com.ayyan.askme.diagnostics.DeathExplainer.lastDeathText());
                            return 1;
                        })
                        .then(ClientCommands.literal("clear")
                                .executes(context -> {
                                    ChatUtil.info(context.getSource().getClient(),
                                            com.ayyan.askme.diagnostics.DeathExplainer.clearDeathPos());
                                    return 1;
                                })))
                .then(ClientCommands.literal("goto")
                        .then(ClientCommands.literal("clear")
                                .executes(context -> {
                                    ChatUtil.info(context.getSource().getClient(), Guide.clear());
                                    return 1;
                                }))
                        .then(ClientCommands.literal("death")
                                .executes(context -> {
                                    Minecraft client = context.getSource().getClient();
                                    int[] p = com.ayyan.askme.diagnostics.DeathExplainer.deathPos();
                                    ChatUtil.info(client, p == null ? "No death position saved."
                                            : Guide.start(client, "death", p[0], p[1], p[2]));
                                    return 1;
                                }))
                        .then(ClientCommands.argument("x", IntegerArgumentType.integer())
                                .then(ClientCommands.argument("y", IntegerArgumentType.integer())
                                        .then(ClientCommands.argument("z", IntegerArgumentType.integer())
                                                .executes(context -> {
                                                    Minecraft client = context.getSource().getClient();
                                                    ChatUtil.info(client, Guide.start(client, "target",
                                                            IntegerArgumentType.getInteger(context, "x"),
                                                            IntegerArgumentType.getInteger(context, "y"),
                                                            IntegerArgumentType.getInteger(context, "z")));
                                                    return 1;
                                                })))))
                .then(ClientCommands.literal("wp")
                        .then(ClientCommands.literal("save")
                                .then(ClientCommands.argument("name", StringArgumentType.word())
                                        .executes(context -> {
                                            Minecraft client = context.getSource().getClient();
                                            ChatUtil.info(client, Waypoints.save(client,
                                                    context.getArgument("name", String.class)));
                                            return 1;
                                        })))
                        .then(ClientCommands.literal("del")
                                .then(ClientCommands.argument("name", StringArgumentType.word())
                                        .executes(context -> {
                                            Minecraft client = context.getSource().getClient();
                                            ChatUtil.info(client, Waypoints.delete(client,
                                                    context.getArgument("name", String.class)));
                                            return 1;
                                        })))
                        .then(ClientCommands.literal("list")
                                .executes(context -> {
                                    Minecraft client = context.getSource().getClient();
                                    ChatUtil.info(client, Waypoints.list(client));
                                    return 1;
                                }))
                        .then(ClientCommands.literal("go")
                                .then(ClientCommands.argument("name", StringArgumentType.word())
                                        .executes(context -> {
                                            Minecraft client = context.getSource().getClient();
                                            String name = context.getArgument("name", String.class);
                                            int[] p = Waypoints.find(client, name);
                                            ChatUtil.info(client, p == null
                                                    ? "No saved place named '" + name + "'. Use /askme wp list."
                                                    : Guide.start(client, name.toLowerCase(Locale.ROOT), p[0], p[1], p[2]));
                                            return 1;
                                        })))
                        .then(ClientCommands.literal("clear")
                                .executes(context -> {
                                    Minecraft client = context.getSource().getClient();
                                    ChatUtil.info(client, Waypoints.clearAll(client));
                                    return 1;
                                })))
                .then(ClientCommands.literal("friend")
                        .then(ClientCommands.literal("add")
                                .then(ClientCommands.argument("name", StringArgumentType.word())
                                        .executes(context -> {
                                            ChatUtil.info(context.getSource().getClient(),
                                                    GameAlerts.friendAdd(context.getArgument("name", String.class)));
                                            return 1;
                                        })))
                        .then(ClientCommands.literal("remove")
                                .then(ClientCommands.argument("name", StringArgumentType.word())
                                        .executes(context -> {
                                            ChatUtil.info(context.getSource().getClient(),
                                                    GameAlerts.friendRemove(context.getArgument("name", String.class)));
                                            return 1;
                                        })))
                        .then(ClientCommands.literal("list")
                                .executes(context -> {
                                    ChatUtil.info(context.getSource().getClient(), GameAlerts.friendList());
                                    return 1;
                                })))
                .then(toggleNode("friendalert", c -> c.friendAlert, (c, v) -> c.friendAlert = v, "Friend join alert"))
                .then(toggleNode("lowhealth", c -> c.lowHealthAlert, (c, v) -> c.lowHealthAlert = v, "Low health alert"))
                .then(ClientCommands.literal("mentions")
                        .executes(context -> {
                            ChatUtil.info(context.getSource().getClient(), Mentions.text());
                            return 1;
                        })
                        .then(ClientCommands.literal("clear")
                                .executes(context -> {
                                    Mentions.clear();
                                    ChatUtil.info(context.getSource().getClient(), "Mentions list cleared.");
                                    return 1;
                                })))
                .then(ClientCommands.literal("playtime")
                        .executes(context -> {
                            ClientControlService.executeControl(context.getSource().getClient(), "playtime");
                            return 1;
                        }))
                .then(ClientCommands.literal("coords")
                        .executes(context -> {
                            Minecraft client = context.getSource().getClient();
                            if (client.player == null) {
                                return 0;
                            }
                            ChatUtil.info(client, "Your position: X " + client.player.getBlockX()
                                    + "  Y " + client.player.getBlockY() + "  Z " + client.player.getBlockZ());
                            return 1;
                        }))
                .then(ClientCommands.literal("info")
                        .executes(context -> {
                            sendInfo(context.getSource().getClient());
                            return 1;
                        }));
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> toggleNode(
            String name,
            Function<AskMeConfig, Boolean> getter,
            BiConsumer<AskMeConfig, Boolean> setter,
            String label) {
        return ClientCommands.literal(name)
                .then(ClientCommands.argument("mode", StringArgumentType.word())
                        .suggests((context, suggestions) ->
                                SharedSuggestionProvider.suggest(List.of("on", "off", "toggle"), suggestions))
                        .executes(context -> {
                            Minecraft client = context.getSource().getClient();
                            AskMeConfig config = AskMeConfig.get();
                            String mode = context.getArgument("mode", String.class).toLowerCase(Locale.ROOT);
                            boolean newValue;
                            switch (mode) {
                                case "on", "true", "enable", "enabled" -> newValue = true;
                                case "off", "false", "disable", "disabled" -> newValue = false;
                                case "toggle" -> newValue = !getter.apply(config);
                                default -> {
                                    ChatUtil.error(client, "Usage: /askme " + name + " <on|off|toggle>");
                                    return 1;
                                }
                            }
                            setter.accept(config, newValue);
                            config.save();
                            ChatUtil.info(client, label + " " + (newValue ? "ENABLED" : "DISABLED") + ".");
                            return 1;
                        }));
    }

    private static int saveApiKey(CommandContext<FabricClientCommandSource> context) {
        Minecraft client = context.getSource().getClient();
        String key = KeyMasker.clean(context.getArgument("key", String.class));
        AskMeConfig config = AskMeConfig.get();
        if (key.equalsIgnoreCase("clear") || key.equalsIgnoreCase("delete")) {
            config.apiKey = "";
            config.save();
            ChatUtil.info(client, "Saved API key deleted.");
            return 1;
        }
        if (!KeyMasker.isValid(key)) {
            ChatUtil.error(client, "Invalid API key format (10-256 non-whitespace characters).");
            return 1;
        }
        config.apiKey = key;
        config.save();
        AskMe.LOGGER.info("[Ask Me] API key updated ({} characters).", key.length());
        ChatUtil.info(client, "API key saved: " + KeyMasker.mask(key));
        return 1;
    }

    private static int setPrefix(CommandContext<FabricClientCommandSource> context) {
        Minecraft client = context.getSource().getClient();
        String word = AiCommands.normalizeKeyword(context.getArgument("new_prefix", String.class));
        if (word.isEmpty()) {
            ChatUtil.error(client, "Invalid prefix. Use 1-32 chars: a-z, 0-9, '-' or '_'.");
            return 1;
        }
        AskMeConfig config = AskMeConfig.get();
        String previous = config.commandKeyword == null || config.commandKeyword.isBlank()
                ? "ask" : config.commandKeyword;
        if (previous.equals(word)) {
            ChatUtil.info(client, "Prefix is already /" + word + ".");
            return 1;
        }
        switch (AiCommands.rewirePrefix(previous, word)) {
            case OK -> {
                config.commandKeyword = word;
                config.save();
                ChatUtil.info(client, "AI prefix updated to /" + word + ".");
            }
            case RESERVED -> ChatUtil.error(client, "/" + word + " is already registered by another command.");
            case FAILED -> {
                config.commandKeyword = word;
                config.save();
                ChatUtil.warn(client, "Saved /" + word + "; full rewire applies on restart.");
            }
        }
        return 1;
    }

    private static int setSystemPrompt(CommandContext<FabricClientCommandSource> context) {
        Minecraft client = context.getSource().getClient();
        String text = context.getArgument("text", String.class).trim();
        AskMeConfig config = AskMeConfig.get();
        if (text.equalsIgnoreCase("clear") || text.isBlank()) {
            config.promptPrefix = "";
            config.save();
            ChatUtil.info(client, "System prompt prefix cleared.");
            return 1;
        }
        config.promptPrefix = text;
        config.save();
        ChatUtil.info(client, "System prompt prefix saved: \"" + preview(text) + "\"");
        return 1;
    }

    private static int setNickname(CommandContext<FabricClientCommandSource> context) {
        Minecraft client = context.getSource().getClient();
        String nickname = context.getArgument("name", String.class).trim();
        AskMeConfig config = AskMeConfig.get();
        if (nickname.equalsIgnoreCase("clear") || nickname.isBlank()) {
            config.nickname = "";
            config.save();
            ChatUtil.info(client, "Nickname cleared.");
            return 1;
        }
        if (nickname.length() > 64 || nickname.contains("\n") || nickname.contains("\r")) {
            ChatUtil.error(client, "Nickname must be 1-64 characters.");
            return 1;
        }
        config.nickname = nickname;
        config.save();
        ChatUtil.info(client, "Nickname set to " + nickname + ".");
        return 1;
    }

    private static int setModel(CommandContext<FabricClientCommandSource> context) {
        Minecraft client = context.getSource().getClient();
        String model = AskMeConfig.normalizeModel(context.getArgument("model", String.class));
        if (model.isEmpty()) {
            ChatUtil.error(client, "Allowed models: " + String.join(", ", AskMeConfig.ALLOWED_MODELS));
            return 1;
        }
        AskMeConfig config = AskMeConfig.get();
        config.geminiModel = model;
        config.geminiEndpoint = AskMeConfig.endpointFor(model);
        config.save();
        ChatUtil.info(client, "Model set to " + model + ".");
        return 1;
    }

    private static String preview(String text) {
        String collapsed = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        return collapsed.length() > TEXT_PREVIEW_LIMIT
                ? collapsed.substring(0, TEXT_PREVIEW_LIMIT - 3) + "..."
                : collapsed;
    }

    private static void sendInfo(Minecraft client) {
        AskMeConfig config = AskMeConfig.get();
        String srvKey = ServerCommandScanner.getCurrentServerKey(client);
        AskMeConfig.ServerCommandCache srvCache = config.getServerScan(srvKey);
        int cachedCmds = srvCache != null ? srvCache.commands.size() : 0;

        MutableComponent out = Component.literal("")
                .append(Component.literal("=== Ask Me ===").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
        out.append(row("Version", AskMe.VERSION, ChatFormatting.WHITE));
        out.append(row("Language", config.language, ChatFormatting.AQUA));
        out.append(row("Model", config.geminiModel, ChatFormatting.WHITE));
        out.append(row("AI Trigger", "/" + config.commandKeyword, ChatFormatting.AQUA));
        out.append(row("Server Cache", srvKey + " (" + cachedCmds + " cmds)", ChatFormatting.GRAY));
        out.append(onOff("Action Execution", config.allowActions));
        out.append(onOff("Client Controls", config.allowClientControls));
        out.append(onOff("Auto Join Scan", config.autoScanOnJoin));
        out.append(onOff("Response Cache", config.enableCache));
        out.append(onOff("Tag Reply", config.autoReply));
        out.append(onOff("Savage Mode", config.savageMode));
        out.append(onOff("Public Reply", config.publicReply));
        out.append(onOff("Reply only to questions", config.replyOnlyQuestions));
        out.append(onOff("Name highlight", config.nameHighlight));
        out.append(row("Name sound", config.nameSound + " (" + config.nameSoundVolume + "%)", ChatFormatting.GRAY));
        out.append(onOff("Mod error watch", config.modWatch));
        out.append(onOff("Auto explain", config.autoExplain));
        out.append(row("Request limit", config.maxRequestsPerMinute + " per minute", ChatFormatting.GRAY));
        out.append(row("API Key",
                config.hasApiKey() ? KeyMasker.mask(config.apiKey) : "<not set>",
                config.hasApiKey() ? ChatFormatting.AQUA : ChatFormatting.RED));
        ChatUtil.raw(client, out);
    }

    private static MutableComponent onOff(String label, boolean value) {
        return Component.literal("\n")
                .append(Component.literal(label + ": ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(value ? "Enabled" : "Disabled")
                        .withStyle(value ? ChatFormatting.GREEN : ChatFormatting.RED));
    }

    private static MutableComponent row(String label, String value, ChatFormatting valueColor) {
        return Component.literal("\n")
                .append(Component.literal(label + ": ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(value == null ? "" : value).withStyle(valueColor));
    }

    // ------------------------------------------------------------------ /askme help

    private static final int HELP_GOLD = 0xFFD54F;
    private static final int HELP_AQUA = 0x55E6FF;
    private static final int HELP_GREEN = 0x6BFF8A;
    private static final int HELP_PURPLE = 0xC792FF;
    private static final int HELP_PINK = 0xFF7AC8;
    private static final int HELP_ORANGE = 0xFFA94D;
    private static final int HELP_TEXT = 0xB8B8B8;
    private static final int HELP_DIM = 0x6B6B6B;

    private static void sendHelp(Minecraft client) {
        String keyword = AskMeConfig.get().commandKeyword;
        String line = "\u2501".repeat(34);

        MutableComponent out = Component.literal("")
                .append(colored("\u2726 ", HELP_GOLD, true))
                .append(gradient("ASK ME  COMMAND GUIDE", 0xFFD54F, 0xFF7AC8))
                .append(colored(" \u2726", HELP_PINK, true))
                .append(Component.literal("\n").append(colored(line, HELP_DIM, false)))
                .append(Component.literal("\n").append(colored("<value> = required   [value] = optional", HELP_DIM, false)));

        out.append(helpSection("AI & CONTROLS", HELP_AQUA));
        out.append(helpEntry("/" + keyword + " <question>", "Ask the AI, or give a game control request", HELP_AQUA));
        out.append(helpEntry("/askme control <op>", "fov, option, optimize, disconnect 10m, bind, press, modconfig", HELP_AQUA));
        out.append(helpEntry("/askme remind <10m|at 23:30> <text>", "Set a reminder (also: remind list, remind cancel)", HELP_AQUA));

        out.append(helpSection("SETUP", HELP_GREEN));
        out.append(helpEntry("/askme apikey <key|clear>", "Save or clear your Gemini API key", HELP_GREEN));
        out.append(helpEntry("/askme model <name>", "Switch the Gemini model", HELP_GREEN));
        out.append(helpEntry("/askme language <lang>", "Roman Urdu, Hinglish, English and more", HELP_GREEN));
        out.append(helpEntry("/askme prefix <word>", "Change the AI command word", HELP_GREEN));
        out.append(helpEntry("/askme limit <n> | usage", "Max AI requests per minute (keeps the free tier safe)", HELP_GREEN));

        out.append(helpSection("SERVER & TAG REPLY", HELP_PURPLE));
        out.append(helpEntry("/askme scan", "Scan this server's commands and /help", HELP_PURPLE));
        out.append(helpEntry("/askme cmds [filter] [page]", "Show the saved command list of this server", HELP_PURPLE));
        out.append(helpEntry("/askme tagreply <on|off>", "Auto-reply when a player tags you", HELP_PURPLE));

        out.append(helpSection("LOOK & SOUND", HELP_PINK));
        out.append(helpEntry("/askme highlight ...", "Your name colour, bold, italic, underline (also: namestyle)", HELP_PINK));
        out.append(helpEntry("/askme sound <id|off|test>", "Game sound when your name appears in chat", HELP_PINK));
        out.append(helpEntry("/askme background <file|default|dim N>", "Own picture for the settings screen", HELP_PINK));

        out.append(helpSection("HEALTH & INFO", HELP_ORANGE));
        out.append(helpEntry("/askme doctor [fix]", "Find mod errors or low FPS and fix what is safe", HELP_ORANGE));
        out.append(helpEntry("/askme optimize [undo]", "More FPS: lower heavy settings (undo restores them)", HELP_ORANGE));
        out.append(helpEntry("/askme explain <on|off>", "Auto-explain why you died or a problem happened", HELP_ORANGE));
        out.append(helpEntry("/askme deathpos", "Show where you died last (saved automatically)", HELP_ORANGE));
        out.append(helpEntry("/askme playtime", "How long you have played in this session", HELP_ORANGE));
        out.append(helpEntry("/askme coords", "Show your current X Y Z position", HELP_ORANGE));
        out.append(helpEntry("/askme info", "Show status and active settings", HELP_ORANGE));

        out.append(helpSection("GAME TOOLS", HELP_GOLD));
        out.append(helpEntry("/askme goto <x> <y> <z>", "Arrow + distance guide to a place; clears itself on arrival", HELP_GOLD));
        out.append(helpEntry("/askme goto death|clear", "Guide to your last death, or stop the guide", HELP_GOLD));
        out.append(helpEntry("/askme wp save|del|go <name>", "Save places (home, farm...), delete them, or be guided there", HELP_GOLD));
        out.append(helpEntry("/askme wp list | wp clear", "List saved places with distance, or delete all places of this server", HELP_GOLD));
        out.append(helpEntry("/askme deathpos [clear]", "Show or forget where you died last", HELP_GOLD));
        out.append(helpEntry("/askme friend add|remove <name> | friend list", "Get an alert when a friend joins or leaves", HELP_GOLD));
        out.append(helpEntry("/askme mentions [clear]", "Who wrote your name in chat while you were busy", HELP_GOLD));
        out.append(helpEntry("/askme lowhealth|friendalert <on|off>", "Switch these alerts on or off", HELP_GOLD));

        out.append(Component.literal("\n").append(colored(line, HELP_DIM, false)));
        out.append(Component.literal("\n").append(colored("Tip: ", HELP_GOLD, true))
                .append(colored("press K to open the Ask Me window. Settings is in its top bar.", HELP_TEXT, false)));

        ChatUtil.raw(client, out);
    }

    private static MutableComponent colored(String text, int rgb, boolean bold) {
        return Component.literal(text).withStyle(style -> style.withColor(rgb).withBold(bold));
    }

    /** Text whose colour slowly changes from one colour to another, letter by letter. */
    private static MutableComponent gradient(String text, int from, int to) {
        MutableComponent out = Component.literal("");
        int last = Math.max(1, text.length() - 1);
        for (int i = 0; i < text.length(); i++) {
            double t = i / (double) last;
            int r = (int) Math.round(((from >> 16) & 0xFF) * (1 - t) + ((to >> 16) & 0xFF) * t);
            int g = (int) Math.round(((from >> 8) & 0xFF) * (1 - t) + ((to >> 8) & 0xFF) * t);
            int b = (int) Math.round((from & 0xFF) * (1 - t) + (to & 0xFF) * t);
            out.append(colored(String.valueOf(text.charAt(i)), (r << 16) | (g << 8) | b, true));
        }
        return out;
    }

    private static MutableComponent helpSection(String title, int rgb) {
        return Component.literal("\n\n")
                .append(colored("\u25C6 ", rgb, true))
                .append(colored(title, rgb, true));
    }

    /** Command on one line, its description on the next line (so long lines never wrap badly). */
    private static MutableComponent helpEntry(String command, String description, int rgb) {
        return Component.literal("\n")
                .append(colored("  \u25B8 ", HELP_DIM, false))
                .append(colored(command, rgb, false))
                .append(Component.literal("\n"))
                .append(colored("      " + description, HELP_TEXT, false));
    }
}
