package com.fulent.appliedfactory.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.client.gui.components.MultilineTextField;

@Mixin(MultilineTextField.class)
public interface MultilineTextFieldAccessor {
    @Accessor("selectCursor")
    int factoryGetSelectCursor();
}
