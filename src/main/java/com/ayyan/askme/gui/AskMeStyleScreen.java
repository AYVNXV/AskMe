package com.ayyan.askme.gui;

import com.ayyan.askme.chat.NameHighlighter;
import com.ayyan.askme.config.AskMeConfig;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;

/**
 * Name highlight and name sound settings. Everything is saved as soon as you click.
 * The preview line shows how your name will look in chat.
 */
public class AskMeStyleScreen extends Screen {

    private static final int W = 300;

    private final Screen parent;
    private StringWidget preview;
    private EditBox colorField;

    public AskMeStyleScreen(Screen parent) {
        super(Component.translatable("askme.style.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        AskMeConfig config = AskMeConfig.get();
        int x0 = this.width / 2 - W / 2;
        int y = 8;

        for (AbstractWidget bg : BackgroundHelper.create(this.width, this.height)) {
            addRenderableWidget(bg);
        }

        addRenderableWidget(new StringWidget(x0, y, W, 9, this.title, this.font));
        y += 16;
        preview = new StringWidget(x0, y, W, 12, previewText(), this.font);
        addRenderableWidget(preview);

        y += 20;
        addToggle(x0, y, W, "askme.tip.style_on",
                () -> Component.translatable(AskMeConfig.get().nameHighlight ? "askme.style.on" : "askme.style.off"),
                () -> AskMeConfig.get().nameHighlight = !AskMeConfig.get().nameHighlight);

        y += 22;
        int third = (W - 4) / 3;
        addToggle(x0, y, third, "askme.tip.style_bold",
                () -> Component.translatable(AskMeConfig.get().highlightBold ? "askme.style.bold_on" : "askme.style.bold_off"),
                () -> AskMeConfig.get().highlightBold = !AskMeConfig.get().highlightBold);
        addToggle(x0 + third + 2, y, third, "askme.tip.style_italic",
                () -> Component.translatable(AskMeConfig.get().highlightItalic ? "askme.style.italic_on" : "askme.style.italic_off"),
                () -> AskMeConfig.get().highlightItalic = !AskMeConfig.get().highlightItalic);
        addToggle(x0 + 2 * (third + 2), y, third, "askme.tip.style_underline",
                () -> Component.translatable(AskMeConfig.get().highlightUnderline ? "askme.style.underline_on" : "askme.style.underline_off"),
                () -> AskMeConfig.get().highlightUnderline = !AskMeConfig.get().highlightUnderline);

        y += 22;
        colorField = new EditBox(this.font, x0, y, 150, 18, Component.translatable("askme.style.color_hint"));
        colorField.setMaxLength(7);
        colorField.setHint(Component.translatable("askme.style.color_hint"));
        colorField.setValue(config.highlightColor);
        colorField.setTooltip(Tooltip.create(Component.translatable("askme.tip.style_color")));
        colorField.setResponder(text -> {
            if (text.matches("#[0-9A-Fa-f]{6}")) {
                AskMeConfig cfg = AskMeConfig.get();
                cfg.highlightColor = text.toUpperCase();
                cfg.save();
                refreshPreview();
            }
        });
        addRenderableWidget(colorField);
        addRenderableWidget(Button.builder(Component.translatable("askme.style.next_color"), button -> {
                    List<String> colors = AskMeConfig.COLOR_PRESETS;
                    AskMeConfig cfg = AskMeConfig.get();
                    int next = (colors.indexOf(cfg.highlightColor.toUpperCase()) + 1) % colors.size();
                    cfg.highlightColor = colors.get(next);
                    cfg.save();
                    colorField.setValue(cfg.highlightColor);
                    refreshPreview();
                })
                .bounds(x0 + 154, y, W - 154, 18)
                .tooltip(Tooltip.create(Component.translatable("askme.tip.style_next_color")))
                .build());

        y += 26;
        addRenderableWidget(Button.builder(soundLabel(), button -> {
                    List<String> sounds = AskMeConfig.SOUND_PRESETS;
                    AskMeConfig cfg = AskMeConfig.get();
                    int next = (sounds.indexOf(cfg.nameSound) + 1) % sounds.size();
                    cfg.nameSound = sounds.get(next);
                    cfg.save();
                    button.setMessage(soundLabel());
                    NameHighlighter.playSoundNow(this.minecraft, cfg.nameSound, cfg.nameSoundVolume);
                })
                .bounds(x0, y, W, 18)
                .tooltip(Tooltip.create(Component.translatable("askme.tip.style_sound")))
                .build());

        y += 22;
        addRenderableWidget(Button.builder(volumeLabel(), button -> {
                    AskMeConfig cfg = AskMeConfig.get();
                    cfg.nameSoundVolume = cfg.nameSoundVolume >= 100 ? 25 : cfg.nameSoundVolume + 25;
                    cfg.save();
                    button.setMessage(volumeLabel());
                    NameHighlighter.playSoundNow(this.minecraft, cfg.nameSound, cfg.nameSoundVolume);
                })
                .bounds(x0, y, W / 2 - 2, 18)
                .tooltip(Tooltip.create(Component.translatable("askme.tip.style_volume")))
                .build());
        addRenderableWidget(Button.builder(Component.translatable("askme.style.test"), button -> {
                    AskMeConfig cfg = AskMeConfig.get();
                    NameHighlighter.playSoundNow(this.minecraft, cfg.nameSound, cfg.nameSoundVolume);
                })
                .bounds(x0 + W / 2 + 2, y, W / 2 - 2, 18)
                .tooltip(Tooltip.create(Component.translatable("askme.tip.style_test")))
                .build());

        y += 30;
        addRenderableWidget(Button.builder(Component.translatable("askme.gui.back"), button -> this.onClose())
                .bounds(this.width / 2 - 50, y, 100, 20)
                .build());
    }

    private void addToggle(int x, int y, int width, String tipKey, Supplier<Component> label, Runnable flip) {
        addRenderableWidget(Button.builder(label.get(), button -> {
                    flip.run();
                    AskMeConfig.get().save();
                    button.setMessage(label.get());
                    refreshPreview();
                })
                .bounds(x, y, width, 18)
                .tooltip(Tooltip.create(Component.translatable(tipKey)))
                .build());
    }

    private Component soundLabel() {
        return Component.translatable("askme.style.sound", AskMeConfig.get().nameSound);
    }

    private Component volumeLabel() {
        return Component.translatable("askme.style.volume", AskMeConfig.get().nameSoundVolume);
    }

    private void refreshPreview() {
        if (preview != null) {
            preview.setMessage(previewText());
        }
    }

    private Component previewText() {
        AskMeConfig config = AskMeConfig.get();
        String name = this.minecraft != null && this.minecraft.player != null
                ? this.minecraft.player.getName().getString() : "YourName";
        Style nameStyle = config.nameHighlight ? NameHighlighter.nameStyle(Style.EMPTY, config) : Style.EMPTY;
        return Component.literal("<Steve> hey ")
                .append(Component.literal(name).withStyle(ignored -> nameStyle))
                .append(Component.literal(", are you there?"));
    }

    /** Dropping an image file on this screen makes it the custom background. */
    public void onFilesDrop(List<Path> paths) {
        if (paths == null || paths.isEmpty()) {
            return;
        }
        try {
            BackgroundHelper.setCustomFrom(paths.get(0));
            this.rebuildWidgets();
        } catch (IOException | RuntimeException ignored) {
            // wrong file type: keep the current background
        }
    }

    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(parent);
    }
}
