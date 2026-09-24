package com.fulent.appliedfactory.client;

import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.systems.RenderSystem;
import com.fulent.appliedfactory.mixin.MultilineTextFieldAccessor;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.components.Whence;
import net.minecraft.client.renderer.RenderType;
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
    // Breathing room on either side of the right-aligned line numbers.
    private static final int GUTTER_PADDING = 4;
    // Below this width the gutter is dropped so code keeps usable space.
    private static final int GUTTER_MIN_PANEL_WIDTH = 200;
    private static final int TEXT_COLOR = 0xFFE0E0E0;
    private static final int CURSOR_COLOR = 0xFFD0D0D0;
    private static final ResourceLocation SCROLLER = ResourceLocation.fromNamespaceAndPath("ae2", "small_scroller");

    private final Font editorFont;
    private final int panelX;
    private final int panelWidth;
    private final List<Integer> lineNumbers = new ArrayList<>();
    private final List<LineRange> displayLines = new ArrayList<>();
    private int gutterWidth;
    private int characterLimit = Integer.MAX_VALUE;
    private boolean editable = true;
    private boolean adjustingGutter;
    private long caretFocusedAt;

    ScriptEditBox(Font font, int x, int y, int width, int height,
            Component placeholder, Component message) {
        // The inherited content bounds exclude our gutter and scrollbar. This
        // keeps vanilla's cursor seeking, wrapping, selection and scroll dragging
        // aligned with what is drawn, without reserving a bottom status row.
        super(font, x + BORDER + initialGutter(width, font), y + BORDER,
                width - BORDER * 2 - initialGutter(width, font) - SCROLLBAR_WIDTH,
                height - BORDER * 2, placeholder, message);
        editorFont = font;
        panelX = x;
        panelWidth = width;
        gutterWidth = initialGutter(width, font);
        super.setValueListener(this::onValueChanged);
        onValueChanged(getValue());
    }

    private static int initialGutter(int panelWidth, Font font) {
        // Prioritize usable code width at small GUI resolutions.
        return panelWidth >= GUTTER_MIN_PANEL_WIDTH ? GUTTER_PADDING * 2 + font.width("0") : 0;
    }

    private void onValueChanged(String value) {
        if (adjustingGutter) {
            return;
        }
        updateGutter(value);
        rebuildDisplayLines(value);
        rebuildLineNumbers(value);
    }

    private void rebuildDisplayLines(String value) {
        displayLines.clear();
        if (value.isEmpty()) {
            displayLines.add(new LineRange(0, 0));
            return;
        }
        editorFont.getSplitter().splitLines(value, getWidth() - totalInnerPadding(),
                Style.EMPTY, false, (style, begin, end) ->
                        displayLines.add(new LineRange(begin, end)));
        if (value.endsWith("\n")) {
            displayLines.add(new LineRange(value.length(), value.length()));
        }
    }

    /** Grows or shrinks the gutter to fit the largest source line number. */
    private void updateGutter(String value) {
        if (panelWidth < GUTTER_MIN_PANEL_WIDTH) {
            return;
        }
        var logicalLines = value.isEmpty() ? 1 : 1 + (int) value.chars().filter(c -> c == '\n').count();
        var desired = GUTTER_PADDING * 2 + editorFont.width(Integer.toString(logicalLines));
        if (desired == gutterWidth) {
            return;
        }
        gutterWidth = desired;
        setX(panelX + BORDER + gutterWidth);
        setWidth(panelWidth - BORDER * 2 - gutterWidth - SCROLLBAR_WIDTH);
        // The vanilla text field caches its wrap width at construction, so re-flow
        // it against the new content width while keeping the caret in place.
        adjustingGutter = true;
        try {
            var cursor = textField.cursor();
            textField.width = getWidth() - totalInnerPadding();
            textField.setValue(value);
            textField.seekCursor(Whence.ABSOLUTE, cursor);
        } finally {
            adjustingGutter = false;
        }
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
    public void setFocused(boolean focused) {
        if (focused && !isFocused()) {
            caretFocusedAt = net.minecraft.Util.getMillis();
        }
        super.setFocused(focused);
    }

    @Override
    protected void renderContents(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        var value = getValue();
        var cursor = textField.cursor();
        var cursorLine = 0;
        for (int index = 0; index < displayLines.size(); index++) {
            var line = displayLines.get(index);
            if (cursor >= line.begin() && cursor <= line.end()) {
                cursorLine = index;
                break;
            }
        }
        var drawCaret = editable && isFocused()
                && (net.minecraft.Util.getMillis() - caretFocusedAt) / 300L % 2L == 0L;
        var hasSelection = textField.hasSelection();
        var selectionAnchor = ((MultilineTextFieldAccessor) textField).factoryGetSelectCursor();
        var selectionStart = Math.min(cursor, selectionAnchor);
        var selectionEnd = Math.max(cursor, selectionAnchor);
        var textX = getX() + innerPadding();
        var lineY = getY() + innerPadding();
        var lineIndex = 0;
        for (var line : displayLines) {
            if (withinContentAreaTopBottom(lineY, lineY + LINE_HEIGHT)) {
                var text = value.substring(line.begin(), line.end());
                graphics.drawString(editorFont, text, textX, lineY, TEXT_COLOR);

                // Adjacent wrapped lines can share a cursor index. Vanilla draws a caret on
                // both because its range check includes both ends; use the chosen line only.
                if (drawCaret && lineIndex == cursorLine) {
                    var caretX = textX + editorFont.width(value.substring(line.begin(), cursor));
                    if (cursor == value.length()) {
                        graphics.drawString(editorFont, "_", caretX - 1, lineY, CURSOR_COLOR);
                    } else {
                        graphics.fill(caretX - 1, lineY - 1,
                                caretX + 1, lineY + LINE_HEIGHT, CURSOR_COLOR);
                    }
                }

                if (hasSelection && selectionStart <= line.end()
                        && line.begin() <= selectionEnd) {
                    var start = textX + editorFont.width(value.substring(
                            line.begin(), Math.max(line.begin(), selectionStart)));
                    var end = selectionEnd > line.end()
                            ? getRight() - innerPadding()
                            : textX + editorFont.width(value.substring(
                                    line.begin(), selectionEnd));
                    if (end > start) {
                        graphics.fill(RenderType.guiTextHighlight(), start, lineY,
                                end, lineY + LINE_HEIGHT, 0xFF0000FF);
                    }
                }
            }
            lineY += LINE_HEIGHT;
            lineIndex++;
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

    private record LineRange(int begin, int end) {
    }
}
