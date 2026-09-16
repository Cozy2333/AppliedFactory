package com.fulent.appliedfactory.client;

import appeng.client.gui.style.BackgroundGenerator;

import net.minecraft.client.gui.GuiGraphics;

/** Shared AE-style surfaces and semantic colors for the factory workspace. */
final class FactoryGuiTheme {
    static final int TEXT = 0xff413f54;
    static final int MUTED = 0xff696d88;
    static final int BORDER = 0xff878fa5;
    static final int LIGHT = 0xfff2f2f2;
    static final int FILES = 0xffb0b3c5;
    static final int FILE_HOVER = 0xffc4c8d8;
    static final int SELECTION = 0xfface9ff;
    static final int EDITOR = 0xff272735;
    static final int GUTTER = 0xff343443;
    static final int EDITOR_MUTED = 0xffa4a7bc;
    static final int SCROLL_TRACK = 0xff4d4d67;
    static final int SUCCESS = 0xff357048;
    static final int WARNING = 0xff835b1b;
    static final int ERROR = 0xffb23d32;

    private FactoryGuiTheme() {
    }

    static void window(GuiGraphics graphics, int x, int y, int width, int height) {
        BackgroundGenerator.draw(width, height, graphics, x, y);
    }

    static void panel(GuiGraphics graphics, int x, int y, int width, int height,
            int border, int fill, int thickness) {
        graphics.fill(x, y, x + width, y + height, border);
        graphics.fill(x + thickness, y + thickness, x + width - thickness, y + height - thickness, fill);
    }
}
