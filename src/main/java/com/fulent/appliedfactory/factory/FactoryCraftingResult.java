package com.fulent.appliedfactory.factory;

import org.jetbrains.annotations.Nullable;

/** Result of advancing a network crafting action for one controller tick. */
public record FactoryCraftingResult(
        Status status,
        @Nullable FactoryResource resource,
        @Nullable String failure) {

    public enum Status {
        WAITING,
        COMPLETED,
        FAILED
    }

    private static final FactoryCraftingResult WAITING =
            new FactoryCraftingResult(Status.WAITING, null, null);

    public static FactoryCraftingResult waiting() {
        return WAITING;
    }

    public static FactoryCraftingResult completed(FactoryResource resource) {
        return new FactoryCraftingResult(Status.COMPLETED, resource, null);
    }

    public static FactoryCraftingResult failed(String message) {
        return new FactoryCraftingResult(Status.FAILED, null, message);
    }
}
