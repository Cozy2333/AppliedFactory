package com.fulent.appliedfactory.client;

import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.systems.RenderSystem;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

/**
 * Multi-line script editor with a proper read-only mode. In read-only mode the
 * widget consumes no keyboard input, so the screen's normal Escape and
 * inventory-key handling closes the GUI without any hard-coded key checks.
 */
final class ScriptEditBox extends MultiLineEditBox {
    private static final int BORDER = 2;
    private static final int SCROLLBAR_WIDTH = 8;
    // Vanilla's multiline layout and cursor seeking both use a 9px line advance.
    private static final int LINE_HEIGHT = 9;
    private static final ResourceLocation SCROLLER = ResourceLocation.fromNamespaceAndPath("ae2", "small_scroller");

    private final Font editorFont;
    private final int gutterWidth;
    private final List<Integer> lineNumbers = new ArrayList<>();
    private int characterLimit = Integer.MAX_VALUE;
    private boolean editable = true;

    ScriptEditBox(Font font, int x, int y, int width, int height,
            Component placeholder, Component message) {
        // The inherited content bounds exclude our gutter and scrollbar. This
        // keeps vanilla's cursor seeking, wrapping, selection and scroll dragging
        // aligned with what is drawn, without reserving a bottom status row.
        super(font, x + BORDER + gutterWidth(width), y + BORDER,
                width - BORDER * 2 - gutterWidth(width) - SCROLLBAR_WIDTH,
                height - BORDER * 2, placeholder, message);
        editorFont = font;
        gutterWidth = gutterWidth(width);
        super.setValueListener(this::rebuildLineNumbers);
        rebuildLineNumbers(getValue());
    }

    private static int gutterWidth(int panelWidth) {
        // Prioritize usable code width at small GUI resolutions.
        return panelWidth >= 200 ? 38 : 0;
    }

    void setEditable(boolean editable) {
        this.editable = editable;
    }

    @Override
    public void setCharacterLimit(int limit) {
        super.setCharacterLimit(limit);
        characterLimit = limit;
    }

    private void rebuildLineNumbers(String value) {
        lineNumbers.clear();
        if (value.isEmpty()) {
            lineNumbers.add(1);
            return;
        }
        int[] scanned = {0};
        int[] logicalLine = {1};
        int[] previousLine = {0};
        editorFont.getSplitter().splitLines(value, getWidth() - totalInnerPadding(), Style.EMPTY, false,
                (style, begin, end) -> {
                    while (scanned[0] < begin) {
                        if (value.charAt(scanned[0]++) == '\n') {
                            logicalLine[0]++;
                        }
                    }
                    // Soft-wrapped continuations do not get another source line number.
                    lineNumbers.add(previousLine[0] == logicalLine[0] ? 0 : logicalLine[0]);
                    previousLine[0] = logicalLine[0];
                });
        if (value.endsWith("\n")) {
            lineNumbers.add(1 + (int) value.chars().filter(c -> c == '\n').count());
        }
    }

    @Override
    protected void renderBackground(GuiGraphics graphics) {
        FactoryGuiTheme.panel(graphics, getX() - gutterWidth - BORDER, getY() - BORDER,
                getWidth() + gutterWidth + SCROLLBAR_WIDTH + BORDER * 2, getHeight() + BORDER * 2,
                FactoryGuiTheme.LIGHT, FactoryGuiTheme.EDITOR, BORDER);
        if (gutterWidth > 0) {
            graphics.fill(getX() - gutterWidth, getY(), getX(), getBottom(), FactoryGuiTheme.GUTTER);
        }
    }

    @Override
    public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.renderWidget(graphics, mouseX, mouseY, partialTick);
        if (!visible || gutterWidth == 0) {
            return;
        }
        graphics.enableScissor(getX() - gutterWidth, getY(), getX(), getBottom());
        graphics.pose().pushPose();
        graphics.pose().translate(0, -scrollAmount(), 0);
        var first = Math.max(0, (int) (scrollAmount() / LINE_HEIGHT) - 1);
        var last = Math.min(lineNumbers.size(), first + getHeight() / LINE_HEIGHT + 3);
        for (int row = first; row < last; row++) {
            var line = lineNumbers.get(row);
            if (line == 0) {
                continue;
            }
            var label = Integer.toString(line);
            var y = getY() + innerPadding() + row * LINE_HEIGHT;
            graphics.drawString(editorFont, label, getX() - 4 - editorFont.width(label), y,
                    FactoryGuiTheme.EDITOR_MUTED, false);
        }
        graphics.pose().popPose();
        graphics.disableScissor();
    }

    @Override
    protected void renderDecorations(GuiGraphics graphics) {
        // Replace vanilla's external counter and scrollbar together. Match its
        // thumb geometry so the inherited drag behavior stays consistent.
        var scrollX = getRight();
        graphics.fill(scrollX, getY(), scrollX + SCROLLBAR_WIDTH, getBottom(), FactoryGuiTheme.SCROLL_TRACK);
        if (scrollbarVisible()) {
            var thumbHeight = Mth.clamp((int) ((float) height * height / (getInnerHeight() + 4)), 32, height);
            var travel = height - thumbHeight;
            var thumbY = getY() + (int) (scrollAmount() * travel / Math.max(1, getMaxScrollAmount()));
            RenderSystem.enableBlend();
            graphics.blitSprite(SCROLLER, scrollX, thumbY, SCROLLBAR_WIDTH, thumbHeight);
            RenderSystem.disableBlend();
        }
        if (characterLimit != Integer.MAX_VALUE) {
            var counter = Component.translatable("gui.multiLineEditBox.character_limit", getValue().length(), characterLimit);
            graphics.enableScissor(getX(), getY(), getRight(), getBottom());
            graphics.drawString(editorFont, counter, getRight() - 4 - editorFont.width(counter),
                    getBottom() - editorFont.lineHeight - 4, FactoryGuiTheme.EDITOR_MUTED, false);
            graphics.disableScissor();
        }
    }

    @Override
    public boolean isMouseOver(double mouseX, double mouseY) {
        return visible && mouseX >= getX() - gutterWidth && mouseX < getRight() + SCROLLBAR_WIDTH
                && mouseY >= getY() && mouseY < getBottom();
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
