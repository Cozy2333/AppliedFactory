package com.fulent.appliedfactory.client;

import java.util.function.BooleanSupplier;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

/** A flat, left-aligned workspace row; selection is independent of keyboard focus. */
final class FactoryFileRowButton extends Button {
    private final BooleanSupplier selected;

    FactoryFileRowButton(int x, int y, int width, int height, Component label,
            BooleanSupplier selected, OnPress onPress) {
        super(x, y, width, height, label, onPress, DEFAULT_NARRATION);
        this.selected = selected;
        setTooltip(Tooltip.create(label));
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (selected.getAsBoolean()) {
            graphics.fill(getX(), getY(), getRight(), getBottom(), FactoryGuiTheme.SELECTION);
            graphics.fill(getX(), getY(), getX() + 2, getBottom(), FactoryGuiTheme.TEXT);
        } else if (isHovered() || isFocused()) {
            graphics.fill(getX(), getY(), getRight(), getBottom(), FactoryGuiTheme.FILE_HOVER);
        }
        var font = Minecraft.getInstance().font;
        var label = getMessage().getString();
        var available = width - 12;
        if (font.width(label) > available) {
            label = font.plainSubstrByWidth(label, Math.max(0, available - font.width("…"))) + "…";
        }
        graphics.drawString(font, label, getX() + 6, getY() + (height - font.lineHeight) / 2,
                FactoryGuiTheme.TEXT, false);
        if (isFocused()) {
            graphics.renderOutline(getX(), getY(), width, height, FactoryGuiTheme.BORDER);
        }
    }
}
