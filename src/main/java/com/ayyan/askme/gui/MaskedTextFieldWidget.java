package com.ayyan.askme.gui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/**
 * An {@link EditBox} that masks its content like a password box while still storing the
 * real characters. The first two characters stay visible, everything else shows as '*'.
 */
public class MaskedTextFieldWidget extends EditBox {

    private static final int VISIBLE_PREFIX_CHARS = 2;

    private boolean reveal;

    public MaskedTextFieldWidget(Font font, int x, int y, int width, int height, Component label) {
        super(font, x, y, width, height, label);
        this.addFormatter(this::maskSlice);
    }

    private FormattedCharSequence maskSlice(String slice, Integer firstCharacterIndex) {
        if (reveal || slice.isEmpty()) {
            return Component.literal(slice).getVisualOrderText();
        }
        int first = firstCharacterIndex == null ? 0 : firstCharacterIndex;
        StringBuilder masked = new StringBuilder(slice.length());
        for (int i = 0; i < slice.length(); i++) {
            masked.append(first + i < VISIBLE_PREFIX_CHARS ? slice.charAt(i) : '*');
        }
        return Component.literal(masked.toString()).getVisualOrderText();
    }

    public void setReveal(boolean reveal) {
        this.reveal = reveal;
    }

    public boolean isRevealing() {
        return reveal;
    }
}