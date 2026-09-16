package com.fulent.appliedfactory.client;

import java.util.function.Consumer;

import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Small modal text prompt used by the workspace file browser for new/rename names. */
final class WorkspaceStringPromptScreen extends Screen {
    private final Component confirmLabel;
    private final String initial;
    private final int maxLength;
    private final Consumer<String> callback;
    private EditBox editBox;

    WorkspaceStringPromptScreen(
            Component title,
            Component confirmLabel,
            String initial,
            int maxLength,
            Consumer<String> callback) {
        super(title);
        this.confirmLabel = confirmLabel;
        this.initial = initial;
        this.maxLength = maxLength;
        this.callback = callback;
    }

    @Override
    protected void init() {
        editBox = new EditBox(font, width / 2 - 120, height / 2 - 12, 240, 20, title);
        editBox.setMaxLength(maxLength);
        editBox.setValue(initial);
        editBox.setFocused(true);
        addRenderableWidget(editBox);
        addRenderableWidget(Button.builder(confirmLabel, ignored -> finish(editBox.getValue()))
                .bounds(width / 2 - 121, height / 2 + 16, 118, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"),
                ignored -> finish(null))
                .bounds(width / 2 + 3, height / 2 + 16, 118, 20).build());
        setInitialFocus(editBox);
    }

    private void finish(@Nullable String value) {
        callback.accept(value);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            finish(editBox.getValue());
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void onClose() {
        finish(null);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, title, width / 2, height / 2 - 34, 0xffffffff);
    }
}
