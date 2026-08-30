package com.fulent.appliedfactory.factory;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.jetbrains.annotations.Nullable;

import appeng.api.networking.crafting.ICraftingLink;

/** Live CPU links used by processing handlers to cancel their parent AE request. */
public final class CraftingRequestRegistry {
    private static final Map<UUID, WeakReference<ICraftingLink>> LINKS =
            new ConcurrentHashMap<>();

    private CraftingRequestRegistry() {
    }

    public static void track(@Nullable ICraftingLink link) {
        LINKS.entrySet().removeIf(entry -> entry.getValue().get() == null);
        if (link != null) {
            LINKS.put(link.getCraftingID(), new WeakReference<>(link));
        }
    }

    public static void forget(@Nullable UUID craftingId) {
        if (craftingId != null) {
            LINKS.remove(craftingId);
        }
    }

    public static boolean cancel(UUID craftingId) {
        var reference = LINKS.get(craftingId);
        var link = reference == null ? null : reference.get();
        if (link == null || link.isDone() || link.isCanceled()) {
            LINKS.remove(craftingId);
            return false;
        }
        link.cancel();
        return true;
    }
}
