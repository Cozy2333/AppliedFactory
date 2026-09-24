package com.fulent.appliedfactory.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.fulent.appliedfactory.factory.CraftingRequestRegistry;

import appeng.crafting.CraftingLink;

/** Report requester-side cancellation immediately, without waiting for the CPU's next tick. */
@Mixin(CraftingLink.class)
public abstract class CraftingLinkMixin {
    @Inject(method = "cancel", at = @At("TAIL"))
    private void factoryOnCraftingLinkCanceled(CallbackInfo ci) {
        var link = (CraftingLink) (Object) this;
        if (link.isCanceled()) {
            CraftingRequestRegistry.canceled(link.getCraftingID());
        }
    }
}
