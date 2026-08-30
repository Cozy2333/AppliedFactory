package com.fulent.appliedfactory.factory;

import net.minecraft.core.Direction;

/** A script request to craft one exact resource on an attached AE network. */
public record FactoryCraftingAction(
        Direction networkSide,
        FactoryResource requested) implements FactoryAction {

    public FactoryCraftingAction {
        if (networkSide == null) {
            throw new IllegalArgumentException("Crafting network side is required");
        }
        if (requested == null || requested.amount() <= 0) {
            throw new IllegalArgumentException("Crafting request must have a positive amount");
        }
    }
}
