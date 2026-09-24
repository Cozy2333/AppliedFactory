package com.fulent.appliedfactory.mixin;

import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.fulent.appliedfactory.blockentity.FactoryControllerBlockEntity;
import com.fulent.appliedfactory.part.FactoryBusPart;

import appeng.api.networking.IGrid;
import appeng.me.Grid;
import appeng.me.GridNode;
import net.minecraft.nbt.CompoundTag;

/** Routes controller and bus node membership events to the controller faces on that grid. */
@Mixin(Grid.class)
public abstract class GridMixin {
    @Inject(method = "add", at = @At("TAIL"))
    private void factoryOnBusAdded(GridNode gridNode, @Nullable CompoundTag savedData, CallbackInfo ci) {
        notifyBusTopologyChanged(gridNode);
    }

    @Inject(method = "remove", at = @At("HEAD"))
    private void factoryOnBusRemoved(GridNode gridNode, CallbackInfo ci) {
        notifyBusTopologyChanged(gridNode);
    }

    private void notifyBusTopologyChanged(GridNode gridNode) {
        if (gridNode.getOwner() instanceof FactoryControllerBlockEntity controller) {
            controller.onControllerNodeTopologyChanged(gridNode);
            return;
        }
        if (!(gridNode.getOwner() instanceof FactoryBusPart)) {
            return;
        }
        var grid = (IGrid) (Object) this;
        for (var controller : grid.getMachines(FactoryControllerBlockEntity.class)) {
            controller.onBusTopologyChanged(grid);
        }
    }
}
