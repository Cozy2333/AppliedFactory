package com.fulent.appliedfactory.factory;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.jetbrains.annotations.Nullable;

import appeng.api.networking.crafting.ICraftingLink;
import com.fulent.appliedfactory.blockentity.FactoryControllerBlockEntity;
import net.minecraft.server.level.ServerLevel;

/** Live CPU links used by processing handlers to cancel their parent AE request. */
public final class CraftingRequestRegistry {
    private static final Map<UUID, WeakReference<ICraftingLink>> LINKS =
            new ConcurrentHashMap<>();
    /** Controllers with processing jobs started by each AE request. */
    private static final Map<UUID, List<WeakReference<FactoryControllerBlockEntity>>> OWNERS =
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
            OWNERS.remove(craftingId);
        }
    }

    public static void registerOwner(@Nullable UUID craftingId, FactoryControllerBlockEntity controller) {
        if (craftingId == null) {
            return;
        }
        OWNERS.compute(craftingId, (id, existing) -> {
            var owners = existing == null
                    ? new ArrayList<WeakReference<FactoryControllerBlockEntity>>()
                    : new ArrayList<>(existing);
            owners.removeIf(reference -> reference.get() == null);
            if (owners.stream().noneMatch(reference -> reference.get() == controller)) {
                owners.add(new WeakReference<>(controller));
            }
            return owners;
        });
    }

    /** Cancellation can originate from the requester link before the CPU ticks again. */
    public static void canceled(@Nullable UUID craftingId) {
        if (craftingId == null) {
            return;
        }
        var owners = OWNERS.remove(craftingId);
        LINKS.remove(craftingId);
        if (owners != null) {
            for (var reference : owners) {
                var controller = reference.get();
                if (controller != null && controller.getLevel() instanceof ServerLevel level) {
                    level.getServer().execute(() -> controller.onCraftingRequestFinished(craftingId));
                }
            }
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
