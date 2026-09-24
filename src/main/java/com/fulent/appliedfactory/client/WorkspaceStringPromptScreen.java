package com.fulent.appliedfactory.client;

import java.util.function.Consumer;

import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import appeng.client.gui.widgets.AE2Button;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Small AE-styled modal text prompt used by the workspace file browser for new/rename names. */
final class WorkspaceStringPromptScreen extends Screen {
    private static final int WINDOW_WIDTH = 240;
    private static final int WINDOW_HEIGHT = 100;
    private static final int PADDING = 14;
    private static final int FIELD_HEIGHT = 20;
    private static final int BUTTON_HEIGHT = 20;
    private static final int BUTTON_GAP = 6;

    private final Component confirmLabel;
    private final String initial;
    private final int maxLength;
    private final Consumer<String> callback;
    private EditBox editBox;
    private int windowX;
    private int windowY;

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
        windowX = (width - WINDOW_WIDTH) / 2;
        windowY = (height - WINDOW_HEIGHT) / 2;

        editBox = new AeTextField(font,
                windowX + PADDING, windowY + 32, WINDOW_WIDTH - PADDING * 2, FIELD_HEIGHT,
                title);
        editBox.setMaxLength(maxLength);
        editBox.setValue(initial);
        addRenderableWidget(editBox);

        var buttonY = windowY + WINDOW_HEIGHT - PADDING - BUTTON_HEIGHT;
        var buttonWidth = (WINDOW_WIDTH - PADDING * 2 - BUTTON_GAP) / 2;
        addRenderableWidget(new AE2Button(windowX + PADDING, buttonY, buttonWidth, BUTTON_HEIGHT,
                confirmLabel, ignored -> finish(editBox.getValue())));
        addRenderableWidget(new AE2Button(
                windowX + PADDING + buttonWidth + BUTTON_GAP, buttonY, buttonWidth, BUTTON_HEIGHT,
                Component.translatable("gui.cancel"), ignored -> finish(null)));
    }

    @Override
    protected void setInitialFocus() {
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
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(graphics, mouseX, mouseY, partialTick);
        FactoryGuiTheme.window(graphics, windowX, windowY, WINDOW_WIDTH, WINDOW_HEIGHT);
        graphics.drawCenteredString(font, title, width / 2, windowY + 12, FactoryGuiTheme.TEXT);
    }

    /** Vanilla single-line box drawn with an AE-style inset surface. */
    private static final class AeTextField extends EditBox {
        AeTextField(Font font, int x, int y, int width, int height, Component message) {
            super(font, x, y, width, height, message);
            setBordered(false);
            setTextColor(FactoryGuiTheme.LIGHT);
            setTextColorUneditable(FactoryGuiTheme.MUTED);
        }

        @Override
        public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            FactoryGuiTheme.panel(graphics, getX(), getY(), getWidth(), getHeight(),
                    FactoryGuiTheme.BORDER, FactoryGuiTheme.EDITOR, 1);
            graphics.pose().pushPose();
            graphics.pose().translate(1.0F, (getHeight() - 8) / 2.0F, 0.0F);
            super.renderWidget(graphics, mouseX, mouseY, partialTick);
            graphics.pose().popPose();
        }
    }
}
