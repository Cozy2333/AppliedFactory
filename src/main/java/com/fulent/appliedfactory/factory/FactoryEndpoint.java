package com.fulent.appliedfactory.factory;

import java.util.Objects;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.Direction;

/** A durable address for a resource endpoint. It never holds a live storage capability. */
public record FactoryEndpoint(
        Kind kind,
        @Nullable Direction networkSide,
        @Nullable FactoryBusAddress bus,
        int slotIndex) {

    public FactoryEndpoint {
        Objects.requireNonNull(kind, "kind");
        if (kind == Kind.NETWORK && networkSide == null) {
            throw new IllegalArgumentException("Network endpoint requires a side");
        }
        if ((kind == Kind.BUS || kind == Kind.SLOT) && bus == null) {
            throw new IllegalArgumentException("Bus endpoint requires an address");
        }
        if (kind == Kind.SLOT && slotIndex < 0) {
            throw new IllegalArgumentException("Slot endpoint requires a non-negative index");
        }
    }

    public static FactoryEndpoint network(Direction side) {
        return new FactoryEndpoint(Kind.NETWORK, Objects.requireNonNull(side), null, -1);
    }

    public static FactoryEndpoint bus(FactoryBusAddress address) {
        return new FactoryEndpoint(Kind.BUS, null, Objects.requireNonNull(address), -1);
    }

    /** One item slot numbered within a bus target's selected face handler. */
    public static FactoryEndpoint itemSlot(FactoryBusAddress address, int index) {
        return new FactoryEndpoint(Kind.SLOT, null, Objects.requireNonNull(address), index);
    }

    public boolean isBusBacked() {
        return kind == Kind.BUS || kind == Kind.SLOT;
    }

    public enum Kind {
        NETWORK,
        BUS,
        SLOT
    }
}
