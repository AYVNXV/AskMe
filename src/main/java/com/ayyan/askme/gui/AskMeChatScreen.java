package com.ayyan.askme.gui;

import com.ayyan.askme.chat.ChatLog;
import com.ayyan.askme.command.AiCommands;
import com.ayyan.askme.config.AskMeConfig;
import com.ayyan.askme.scanner.ServerCommandScanner;
import com.ayyan.askme.util.ChatUtil;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class AskMeChatScreen extends Screen {

    private static final int PANEL_WIDTH = 300;
    private static final int LINE_HEIGHT = 10;
    private static final int TOP = 32;
    private static final int BOTTOM_RESERVED = 32;

    private static final Set<String> MANAGE_WORDS = Set.of(
            "apikey", "prefix", "language", "control", "scan", "cmds",
            "systemprompt", "addnickname", "action", "controls", "autoscan",
            "cache", "reset", "model", "clearhistory", "info", "tagreply",
            "savage", "publicreply", "cooldown", "deathpos", "coords", "playtime", "wp", "friend", "mentions",
            "lowhealth", "friendalert", "goto");

    private record Line(String text, int color) {
    }

    private final Screen parent;
    private final List<StringWidget> lineWidgets = new ArrayList<>();

    private EditBox input;
    private int renderedVersion = -1;
    private int renderedScroll = -1;
    private int scroll;

    public AskMeChatScreen(Screen parent) {
        super(Component.translatable("askme.chat.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        lineWidgets.clear();
        renderedVersion = -1;
        renderedScroll = -1;

        if (ChatLog.isEmpty()) {
            ChatLog.add(ChatLog.Kind.SYSTEM, Component.translatable("askme.chat.welcome").getString());
        }

        int x0 = this.width / 2 - PANEL_WIDTH / 2;
        int x = this.width / 2 - 144;

        addRenderableWidget(Button.builder(Component.translatable("askme.chat.settings"),
                        button -> this.minecraft.gui.setScreen(new AskMeConfigScreen(this)))
                .bounds(x, 6, 62, 20)
                .tooltip(Tooltip.create(Component.translatable("askme.tip.settings")))
                .build());
        x += 64;
        addRenderableWidget(Button.builder(Component.translatable("askme.chat.scan"), button -> {
                    ServerCommandScanner.triggerScan(this.minecraft, true);
                    ChatUtil.info(this.minecraft, "Server command and /help scan triggered.");
                })
                .bounds(x, 6, 52, 20)
                .tooltip(Tooltip.create(Component.translatable("askme.tip.scan_now")))
                .build());
        x += 54;
        addRenderableWidget(Button.builder(Component.translatable("askme.chat.clear"), button -> {
                    ChatLog.clear();
                    scroll = 0;
                })
                .bounds(x, 6, 50, 20)
                .tooltip(Tooltip.create(Component.translatable("askme.tip.clear")))
                .build());
        x += 52;
        addRenderableWidget(Button.builder(autoReplyLabel(), button -> {
                    AskMeConfig config = AskMeConfig.get();
                    config.autoReply = !config.autoReply;
                    config.save();
                    button.setMessage(autoReplyLabel());
                })
                .bounds(x, 6, 68, 20)
                .tooltip(Tooltip.create(Component.translatable("askme.tip.autoreply")))
                .build());
        x += 70;
        addRenderableWidget(Button.builder(Component.translatable("askme.chat.close"), button -> this.onClose())
                .bounds(x, 6, 50, 20)
                .tooltip(Tooltip.create(Component.translatable("askme.tip.close")))
                .build());

        input = new EditBox(this.font, x0, this.height - 26, PANEL_WIDTH - 54, 20,
                Component.translatable("askme.chat.hint"));
        input.setMaxLength(500);
        input.setHint(Component.translatable("askme.chat.hint"));
        input.setTooltip(Tooltip.create(Component.translatable("askme.tip.chat_input")));
        addRenderableWidget(input);
        this.setInitialFocus(input);

        addRenderableWidget(Button.builder(Component.translatable("askme.chat.send"), button -> submit())
                .bounds(x0 + PANEL_WIDTH - 50, this.height - 26, 50, 20)
                .tooltip(Tooltip.create(Component.translatable("askme.tip.send")))
                .build());

        rebuildLines();
    }

    private Component autoReplyLabel() {
        return Component.translatable(AskMeConfig.get().autoReply
                ? "askme.chat.autoreply_on" : "askme.chat.autoreply_off");
    }

    @Override
    public void tick() {
        super.tick();
        if (renderedVersion != ChatLog.version() || renderedScroll != scroll) {
            rebuildLines();
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        scroll += scrollY > 0 ? 3 : -3;
        if (scroll < 0) {
            scroll = 0;
        }
        return true;
    }

    private void rebuildLines() {
        for (StringWidget widget : lineWidgets) {
            removeWidget(widget);
        }
        lineWidgets.clear();

        int x0 = this.width / 2 - PANEL_WIDTH / 2;
        List<Line> all = new ArrayList<>();
        for (ChatLog.Entry entry : ChatLog.snapshot()) {
            String prefix;
            int color;
            switch (entry.kind) {
                case USER -> {
                    prefix = "You: ";
                    color = 0xFFFFFFFF;
                }
                case AI -> {
                    prefix = "AI: ";
                    color = 0xFF55FFFF;
                }
                case TAG -> {
                    prefix = "[Tag] ";
                    color = 0xFFFFAA00;
                }
                default -> {
                    prefix = "";
                    color = 0xFFAAAAAA;
                }
            }
            for (String wrapped : wrap(prefix + entry.text, PANEL_WIDTH)) {
                all.add(new Line(wrapped, color));
            }
        }

        int visible = Math.max(1, (this.height - BOTTOM_RESERVED - TOP) / LINE_HEIGHT);
        int maxScroll = Math.max(0, all.size() - visible);
        scroll = Math.min(Math.max(scroll, 0), maxScroll);

        int end = all.size() - scroll;
        int start = Math.max(0, end - visible);
        int y = this.height - BOTTOM_RESERVED - (end - start) * LINE_HEIGHT;
        for (int i = start; i < end; i++) {
            Line line = all.get(i);
            StringWidget widget = new StringWidget(x0, y, PANEL_WIDTH, 9,
                    Component.literal(line.text()).withColor(line.color()), this.font);
            addRenderableWidget(widget);
            lineWidgets.add(widget);
            y += LINE_HEIGHT;
        }

        renderedVersion = ChatLog.version();
        renderedScroll = scroll;
    }

    private List<String> wrap(String text, int maxWidth) {
        List<String> result = new ArrayList<>();
        for (String paragraph : text.split("\\r?\\n", -1)) {
            StringBuilder current = new StringBuilder();
            for (String word : paragraph.split(" ")) {
                String candidate = current.length() == 0 ? word : current + " " + word;
                if (this.font.width(candidate) <= maxWidth) {
                    current = new StringBuilder(candidate);
                    continue;
                }
                if (current.length() > 0) {
                    result.add(current.toString());
                    current = new StringBuilder();
                }
                String rest = word;
                while (this.font.width(rest) > maxWidth && rest.length() > 1) {
                    String part = this.font.plainSubstrByWidth(rest, maxWidth);
                    if (part.isEmpty()) {
                        break;
                    }
                    result.add(part);
                    rest = rest.substring(part.length());
                }
                current.append(rest);
            }
            result.add(current.toString());
        }
        return result;
    }

    private void submit() {
        String raw = input.getValue() == null ? "" : input.getValue().trim();
        input.setValue("");
        if (raw.isEmpty()) {
            return;
        }
        scroll = 0;

        if (!raw.startsWith("/")) {
            AiCommands.runAsk(this.minecraft, raw);
            return;
        }

        String body = raw.substring(1).trim();
        if (body.isEmpty()) {
            return;
        }
        String word = body.split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        switch (word) {
            case "help" -> showHelp();
            case "settings" -> this.minecraft.gui.setScreen(new AskMeConfigScreen(this));
            case "clear" -> ChatLog.clear();
            default -> runClientCommand(MANAGE_WORDS.contains(word) ? "askme " + body : body);
        }
    }

    private void runClientCommand(String command) {
        if (this.minecraft.player == null || this.minecraft.player.connection == null) {
            return;
        }
        this.minecraft.player.connection.sendCommand(command);
    }

    private void showHelp() {
        ChatUtil.info(this.minecraft, "Type a question or control request and press Send.");
        ChatUtil.info(this.minecraft, "/settings  /scan  /cmds  /language <lang>  /control <op>");
    }

    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(parent);
    }
}