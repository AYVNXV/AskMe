package com.ayyan.askme.gui;

import com.ayyan.askme.AskMe;
import com.ayyan.askme.command.AiCommands;
import com.ayyan.askme.config.AskMeConfig;
import com.ayyan.askme.config.KeyMasker;
import com.ayyan.askme.scanner.ServerCommandScanner;
import com.ayyan.askme.util.ChatUtil;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;

public class AskMeConfigScreen extends Screen {

    private static final int W = 300;

    private final Screen parent;

    private MaskedTextFieldWidget apiKeyField;
    private EditBox nicknameField;
    private EditBox keywordField;
    private EditBox languageField;
    private EditBox promptField;
    private Button showAllButton;
    private Button modelButton;
    private Button langCycleButton;
    private StringWidget status;

    private boolean showApiKey;
    private String selectedModel = AskMeConfig.DEFAULT_MODEL;
    private String selectedLanguage = AskMeConfig.DEFAULT_LANGUAGE;

    public AskMeConfigScreen(Screen parent) {
        super(Component.translatable("askme.gui.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        AskMeConfig config = AskMeConfig.get();
        int x0 = this.width / 2 - W / 2;

        // Full-screen background (added first, so it is drawn behind everything else).
        for (AbstractWidget bg : BackgroundHelper.create(this.width, this.height)) {
            addRenderableWidget(bg);
        }

        selectedModel = AskMeConfig.normalizeModel(config.geminiModel);
        if (selectedModel.isEmpty()) {
            selectedModel = AskMeConfig.DEFAULT_MODEL;
        }
        selectedLanguage = (config.language == null || config.language.isBlank())
                ? AskMeConfig.DEFAULT_LANGUAGE : config.language;

        addRenderableWidget(new StringWidget(x0, 6, W, 9, this.title, this.font));
        status = new StringWidget(x0, 17, W, 9,
                Component.translatable("askme.gui.subtitle").withStyle(ChatFormatting.GRAY), this.font);
        addRenderableWidget(status);

        int y = 30;
        apiKeyField = new MaskedTextFieldWidget(this.font, x0, y, 186, 18,
                Component.translatable("askme.gui.api_key_label"));
        apiKeyField.setMaxLength(256);
        apiKeyField.setHint(Component.translatable("askme.gui.api_key_label"));
        apiKeyField.setValue(config.apiKey == null ? "" : config.apiKey);
        apiKeyField.setTooltip(Tooltip.create(Component.translatable("askme.tip.api_key")));
        addRenderableWidget(apiKeyField);
        this.setInitialFocus(apiKeyField);

        showAllButton = Button.builder(showHideLabel(), button -> toggleShow())
                .bounds(x0 + 190, y, 54, 18)
                .tooltip(Tooltip.create(Component.translatable("askme.tip.show")))
                .build();
        addRenderableWidget(showAllButton);

        addRenderableWidget(Button.builder(Component.translatable("askme.gui.copy"), button -> copyKey())
                .bounds(x0 + 248, y, 52, 18)
                .tooltip(Tooltip.create(Component.translatable("askme.tip.copy")))
                .build());

        y += 21;
        nicknameField = textField(x0, y, W, 64, "askme.gui.nickname_label", "askme.tip.nickname",
                config.nickname == null ? "" : config.nickname);

        y += 21;
        keywordField = textField(x0, y, 146, 32, "askme.gui.command_prefix_label", "askme.tip.keyword",
                config.commandKeyword == null ? "ask" : config.commandKeyword);
        modelButton = Button.builder(modelLabel(), button -> {
                    List<String> models = AskMeConfig.ALLOWED_MODELS;
                    int next = (models.indexOf(selectedModel) + 1) % models.size();
                    selectedModel = models.get(next);
                    button.setMessage(modelLabel());
                })
                .bounds(x0 + 150, y, 150, 18)
                .tooltip(Tooltip.create(Component.translatable("askme.tip.model")))
                .build();
        addRenderableWidget(modelButton);

        y += 21;
        languageField = textField(x0, y, 166, 48, "askme.gui.language_label", "askme.tip.language",
                selectedLanguage);
        langCycleButton = Button.builder(Component.translatable("askme.gui.cycle_lang"), button -> {
                    List<String> langs = AskMeConfig.SUPPORTED_LANGUAGES;
                    String current = languageField.getValue() == null ? selectedLanguage : languageField.getValue().trim();
                    int idx = -1;
                    for (int i = 0; i < langs.size(); i++) {
                        if (langs.get(i).equalsIgnoreCase(current)) {
                            idx = i;
                            break;
                        }
                    }
                    selectedLanguage = langs.get((idx + 1) % langs.size());
                    languageField.setValue(selectedLanguage);
                })
                .bounds(x0 + 170, y, 130, 18)
                .tooltip(Tooltip.create(Component.translatable("askme.tip.language")))
                .build();
        addRenderableWidget(langCycleButton);

        y += 21;
        promptField = textField(x0, y, W, 400, "askme.gui.prompt_prefix_label", "askme.tip.prompt",
                config.promptPrefix == null ? "" : config.promptPrefix);

        y += 21;
        addToggle(x0, y, "askme.tip.actions",
                () -> Component.translatable(AskMeConfig.get().allowActions
                        ? "askme.gui.actions_on" : "askme.gui.actions_off"),
                () -> AskMeConfig.get().allowActions = !AskMeConfig.get().allowActions);
        addToggle(x0 + W / 2 + 2, y, "askme.tip.controls",
                () -> Component.translatable(AskMeConfig.get().allowClientControls
                        ? "askme.gui.controls_on" : "askme.gui.controls_off"),
                () -> AskMeConfig.get().allowClientControls = !AskMeConfig.get().allowClientControls);

        y += 21;
        addToggle(x0, y, "askme.tip.autoreply",
                () -> Component.translatable(AskMeConfig.get().autoReply
                        ? "askme.gui.autoreply_on" : "askme.gui.autoreply_off"),
                () -> AskMeConfig.get().autoReply = !AskMeConfig.get().autoReply);
        addToggle(x0 + W / 2 + 2, y, "askme.tip.savage",
                () -> Component.translatable(AskMeConfig.get().savageMode
                        ? "askme.gui.savage_on" : "askme.gui.savage_off"),
                () -> AskMeConfig.get().savageMode = !AskMeConfig.get().savageMode);

        y += 21;
        addToggle(x0, y, "askme.tip.public",
                () -> Component.translatable(AskMeConfig.get().publicReply
                        ? "askme.gui.public_on" : "askme.gui.public_off"),
                () -> AskMeConfig.get().publicReply = !AskMeConfig.get().publicReply);
        addRenderableWidget(Button.builder(Component.translatable("askme.gui.scan_now"), button -> {
                    ServerCommandScanner.triggerScan(this.minecraft, true);
                    setStatus(Component.translatable("askme.gui.scanning").withStyle(ChatFormatting.AQUA));
                })
                .bounds(x0 + W / 2 + 2, y, W / 2 - 2, 18)
                .tooltip(Tooltip.create(Component.translatable("askme.tip.scan_now")))
                .build());

        y += 21;
        addRenderableWidget(Button.builder(Component.translatable("askme.gui.delete_key"), button -> deleteKey())
                .bounds(x0, y, W / 2 - 2, 18)
                .tooltip(Tooltip.create(Component.translatable("askme.tip.delete_key")))
                .build());
        addRenderableWidget(Button.builder(Component.translatable("askme.gui.clear_history"), button -> clearHistory())
                .bounds(x0 + W / 2 + 2, y, W / 2 - 2, 18)
                .tooltip(Tooltip.create(Component.translatable("askme.tip.clear_history")))
                .build());

        y += 21;
        addRenderableWidget(Button.builder(Component.translatable("askme.gui.style_button"),
                        button -> this.minecraft.gui.setScreen(new AskMeStyleScreen(this)))
                .bounds(x0, y, W / 2 - 2, 18)
                .tooltip(Tooltip.create(Component.translatable("askme.tip.style_button")))
                .build());
        addRenderableWidget(Button.builder(Component.translatable(AskMeConfig.get().useCustomBackground
                                ? "askme.gui.bg_custom" : "askme.gui.bg_default"), button -> toggleBackground())
                .bounds(x0 + W / 2 + 2, y, W / 2 - 2, 18)
                .tooltip(Tooltip.create(Component.translatable("askme.tip.bg_button")))
                .build());

        y += 25;
        addRenderableWidget(Button.builder(Component.translatable("askme.gui.save"), button -> saveAll())
                .bounds(this.width / 2 - 100, y, 98, 20)
                .tooltip(Tooltip.create(Component.translatable("askme.tip.save")))
                .build());
        addRenderableWidget(Button.builder(Component.translatable("askme.gui.back"), button -> this.onClose())
                .bounds(this.width / 2 + 2, y, 98, 20)
                .tooltip(Tooltip.create(Component.translatable("askme.tip.back")))
                .build());
    }

    private EditBox textField(int x, int y, int width, int maxLength, String hintKey, String tipKey, String value) {
        EditBox box = new EditBox(this.font, x, y, width, 18, Component.translatable(hintKey));
        box.setMaxLength(maxLength);
        box.setHint(Component.translatable(hintKey));
        box.setValue(value);
        box.setTooltip(Tooltip.create(Component.translatable(tipKey)));
        addRenderableWidget(box);
        return box;
    }

    private void addToggle(int x, int y, String tipKey, Supplier<Component> label, Runnable flip) {
        addRenderableWidget(Button.builder(label.get(), button -> {
                    flip.run();
                    AskMeConfig.get().save();
                    button.setMessage(label.get());
                })
                .bounds(x, y, W / 2 - 2, 18)
                .tooltip(Tooltip.create(Component.translatable(tipKey)))
                .build());
    }

    private Component showHideLabel() {
        return Component.translatable(showApiKey ? "askme.gui.hide_all" : "askme.gui.show_all");
    }

    private Component modelLabel() {
        return Component.translatable("askme.gui.model_button", selectedModel);
    }

    private void toggleShow() {
        showApiKey = !showApiKey;
        apiKeyField.setReveal(showApiKey);
        showAllButton.setMessage(showHideLabel());
    }

    private void copyKey() {
        String value = apiKeyField.getValue() == null ? "" : apiKeyField.getValue().trim();
        if (value.isEmpty()) {
            setStatus(Component.translatable("askme.gui.no_key_hint").withStyle(ChatFormatting.RED));
            return;
        }
        this.minecraft.keyboardHandler.setClipboard(value);
        setStatus(Component.translatable("askme.gui.copied").withStyle(ChatFormatting.GREEN));
    }

    private void deleteKey() {
        apiKeyField.setValue("");
        AskMeConfig config = AskMeConfig.get();
        config.apiKey = "";
        config.save();
        setStatus(Component.translatable("askme.gui.key_deleted").withStyle(ChatFormatting.GREEN));
    }

    private void clearHistory() {
        AskMeConfig.get().clearConversationHistory();
        setStatus(Component.translatable("askme.gui.history_cleared").withStyle(ChatFormatting.GREEN));
    }

    private void setStatus(Component message) {
        status.setMessage(message);
    }

    /** Switch between the bundled picture and the custom picture (config/askme-bg.png). */
    private void toggleBackground() {
        AskMeConfig config = AskMeConfig.get();
        if (!config.useCustomBackground && !BackgroundHelper.hasCustomFile()) {
            setStatus(Component.translatable("askme.gui.bg_none").withStyle(ChatFormatting.YELLOW));
            return;
        }
        config.useCustomBackground = !config.useCustomBackground;
        config.save();
        BackgroundHelper.invalidate();
        saveAll();
        this.rebuildWidgets();
    }

    /** Dropping an image file on this screen makes it the custom background. (Screen hook for dropped files.) */
    public void onFilesDrop(List<Path> paths) {
        if (paths == null || paths.isEmpty()) {
            return;
        }
        try {
            BackgroundHelper.setCustomFrom(paths.get(0));
            saveAll();
            this.rebuildWidgets();
            setStatus(Component.translatable("askme.gui.bg_set").withStyle(ChatFormatting.GREEN));
        } catch (IOException | RuntimeException problem) {
            setStatus(Component.literal(String.valueOf(problem.getMessage())).withStyle(ChatFormatting.RED));
        }
    }

    private void saveAll() {
        AskMeConfig config = AskMeConfig.get();

        String newKeyword = AiCommands.normalizeKeyword(keywordField.getValue());
        if (newKeyword.isEmpty()) {
            setStatus(Component.translatable("askme.gui.bad_prefix").withStyle(ChatFormatting.RED));
            return;
        }

        String keyCandidate = KeyMasker.clean(apiKeyField.getValue());
        if (!keyCandidate.isEmpty() && !KeyMasker.isValid(keyCandidate)) {
            setStatus(Component.translatable("askme.gui.bad_key").withStyle(ChatFormatting.RED));
            return;
        }

        String previous = config.commandKeyword == null || config.commandKeyword.isBlank()
                ? "ask" : config.commandKeyword;
        if (!previous.equals(newKeyword)) {
            switch (AiCommands.rewirePrefix(previous, newKeyword)) {
                case OK -> config.commandKeyword = newKeyword;
                case RESERVED -> {
                    setStatus(Component.translatable("askme.gui.reserved", newKeyword).withStyle(ChatFormatting.RED));
                    return;
                }
                case FAILED -> {
                    config.commandKeyword = newKeyword;
                    ChatUtil.warn(this.minecraft, "/" + newKeyword + " saved; full rewire applies on restart.");
                }
            }
        }

        String typedLang = languageField.getValue() == null ? "" : languageField.getValue().trim();
        String newLanguage = typedLang.isEmpty() ? AskMeConfig.DEFAULT_LANGUAGE : typedLang;
        if (!newLanguage.equals(config.language)) {
            config.setLanguage(newLanguage); // also forgets old turns, so the old language is not copied
        }
        config.apiKey = keyCandidate;
        config.nickname = nicknameField.getValue() == null ? "" : nicknameField.getValue().trim();
        config.promptPrefix = promptField.getValue() == null ? "" : promptField.getValue().trim();
        config.geminiModel = selectedModel;
        config.geminiEndpoint = AskMeConfig.endpointFor(selectedModel);
        config.save();

        AskMe.LOGGER.info("[Ask Me] Settings saved (keyword={}, lang={}, model={}).",
                config.commandKeyword, config.language, config.geminiModel);
        setStatus(Component.translatable("askme.gui.saved").withStyle(ChatFormatting.GREEN));
    }

    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(parent);
    }
}