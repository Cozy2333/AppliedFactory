package com.fulent.appliedfactory.client;

import com.mojang.blaze3d.systems.RenderSystem;

import appeng.client.gui.Icon;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/** AE toolbar chrome with factory artwork, accessible labels and toggle states. */
final class FactoryIconButton extends Button {
    static final int WIDTH = 18;
    static final int HEIGHT = 20;

    enum Symbol {
        UPLOAD("upload"), DOWNLOAD("download"), FOLDER("folder"), CODE("code"),
        LINK("link"), LOG("log"), REFRESH("refresh"), NEW("new"),
        DELETE("delete"), RENAME("rename"), WORKSPACE(Icon.CRAFT_HAMMER),
        PAGE_UP(Icon.S_ARROW_UP), PAGE_DOWN(Icon.S_ARROW_DOWN);

        private final ResourceLocation texture;
        private final Icon aeIcon;

        Symbol(String name) {
            texture = ResourceLocation.fromNamespaceAndPath("appliedfactory", "textures/gui/icons/" + name + ".png");
            aeIcon = null;
        }

        Symbol(Icon icon) {
            texture = null;
            aeIcon = icon;
        }

        void draw(GuiGraphics graphics, int x, int y, int width, int height, float opacity) {
            if (aeIcon != null) {
                aeIcon.getBlitter().opacity(opacity)
                        .dest(x + (width - aeIcon.width) / 2, y + (height - aeIcon.height) / 2)
                        .blit(graphics);
            } else {
                graphics.blit(texture, x + (width - 16) / 2, y + (height - 16) / 2,
                        0, 0, 16, 16, 16, 16);
            }
        }
    }

    private final Symbol symbol;
    private final boolean compact;
    private boolean selected;

    FactoryIconButton(int x, int y, Symbol symbol, String tooltipKey, OnPress onPress) {
        this(x, y, WIDTH, HEIGHT, symbol, tooltipKey, onPress);
    }

    FactoryIconButton(int x, int y, int width, int height, Symbol symbol, String tooltipKey, OnPress onPress) {
        super(x, y, width, height, Component.translatable(tooltipKey), onPress, DEFAULT_NARRATION);
        this.symbol = symbol;
        compact = width < WIDTH || height < HEIGHT;
        setTooltip(Tooltip.create(getMessage()));
    }

    void setSelected(boolean selected) {
        this.selected = selected;
    }

    void setActionLabel(String key) {
        setMessage(Component.translatable(key));
        setTooltip(Tooltip.create(getMessage()));
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        RenderSystem.enableBlend();
        if (compact) {
            if (active && (isHovered() || isFocused())) {
                graphics.fill(getX(), getY(), getRight(), getBottom(), FactoryGuiTheme.SELECTION);
            }
        } else {
            var background = active && isHovered() ? Icon.TOOLBAR_BUTTON_BACKGROUND_HOVER
                    : isFocused() || selected ? Icon.TOOLBAR_BUTTON_BACKGROUND_FOCUS
                    : Icon.TOOLBAR_BUTTON_BACKGROUND;
            background.getBlitter().dest(getX(), getY()).blit(graphics);
        }
        graphics.setColor(1, 1, 1, active ? alpha : alpha * 0.35f);
        symbol.draw(graphics, getX(), getY(), width, height, active ? alpha : alpha * 0.35f);
        graphics.setColor(1, 1, 1, 1);
        if (selected && !compact) {
            graphics.fill(getX() + 3, getBottom() - 3, getRight() - 3, getBottom() - 2, FactoryGuiTheme.SELECTION);
        }
        RenderSystem.disableBlend();
    }
}
