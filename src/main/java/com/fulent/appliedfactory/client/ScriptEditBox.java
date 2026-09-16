package com.fulent.appliedfactory.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.network.chat.Component;

/**
 * Multi-line script editor with a proper read-only mode. In read-only mode the
 * widget consumes no keyboard input, so the screen's normal Escape and
 * inventory-key handling closes the GUI without any hard-coded key checks.
 */
final class ScriptEditBox extends MultiLineEditBox {
    private boolean editable = true;

    ScriptEditBox(Font font, int x, int y, int width, int height,
            Component placeholder, Component message) {
        super(font, x, y, width, height, placeholder, message);
    }

    void setEditable(boolean editable) {
        this.editable = editable;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (!editable) {
            return false;
        }
        // While editing, the inventory key must type instead of closing the
        // screen. Comparing against the live binding respects user rebinds.
        if (Minecraft.getInstance().options.keyInventory.matches(keyCode, scanCode)) {
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        return editable && super.charTyped(codePoint, modifiers);
    }
}
