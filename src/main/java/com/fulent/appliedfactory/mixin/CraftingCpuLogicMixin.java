package com.fulent.appliedfactory.mixin;

import java.util.UUID;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.fulent.appliedfactory.AppliedFactory;
import com.fulent.appliedfactory.factory.CraftingRequestContext;
import com.fulent.appliedfactory.factory.CraftingRequestRegistry;

import appeng.api.networking.crafting.ICraftingLink;
import appeng.api.networking.energy.IEnergyService;
import appeng.crafting.execution.CraftingCpuLogic;
import appeng.me.service.CraftingService;
import net.minecraft.world.level.Level;

/**
 * Links factory jobs to their AE crafting request. AE2 does not notify crafting providers when
 * an order ends, so the request id is captured while its patterns are pushed.
 *
 * <ul>
 *   <li>{@code executeCrafting} pushes patterns for one crafting request at a time — the request's
 *       id is captured into {@link CraftingRequestContext} so the factory can stamp its jobs.
 *   <li>{@code finishJob(success)} clears the owner registration on success and catches failed
 *       jobs that end without an earlier link cancellation. The link mixin handles cancellation
 *       when it happens, even if the CPU never advances again.
 * </ul>
 */
@Mixin(CraftingCpuLogic.class)
public abstract class CraftingCpuLogicMixin {
    @Inject(method = "executeCrafting", at = @At("HEAD"))
    private void factoryCaptureCraftingRequestId(
            int maxPatterns, CraftingService craftingService,
            IEnergyService energyService, Level level,
            CallbackInfoReturnable<Integer> cir) {
        try {
            var link = ((CraftingCpuLogic) (Object) this).getLastLink();
            CraftingRequestRegistry.track(link);
            CraftingRequestContext.set(link == null ? null : link.getCraftingID());
        } catch (RuntimeException exception) {
            AppliedFactory.LOGGER.error("Failed to capture the factory crafting request id", exception);
        }
    }

    @Inject(method = "executeCrafting", at = @At("RETURN"))
    private void factoryClearCraftingRequestId(
            int maxPatterns, CraftingService craftingService,
            IEnergyService energyService, Level level,
            CallbackInfoReturnable<Integer> cir) {
        CraftingRequestContext.clear();
    }

    @Inject(method = "finishJob", at = @At("HEAD"))
    private void factoryNotifyCraftingRequestFinished(boolean success, CallbackInfo ci) {
        var craftingId = craftingIdOf((CraftingCpuLogic) (Object) this);
        if (!success) {
            CraftingRequestRegistry.canceled(craftingId);
        } else {
            CraftingRequestRegistry.forget(craftingId);
        }
    }

    private static UUID craftingIdOf(CraftingCpuLogic logic) {
        ICraftingLink link = logic.getLastLink();
        return link == null ? null : link.getCraftingID();
    }
}
