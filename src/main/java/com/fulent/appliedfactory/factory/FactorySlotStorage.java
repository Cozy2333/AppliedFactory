package com.fulent.appliedfactory.factory;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.MEStorage;

import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * One exact item slot of an external container exposed as an {@link MEStorage}.
 *
 * <p>The wrapper works directly on the container's whole-inventory handler
 * instead of the accessed face's capability view, so it can move resources
 * through slots the face forbids for input or output.</p>
 */
final class FactorySlotStorage implements MEStorage {
    private final IItemHandler handler;
    private final int slot;

    FactorySlotStorage(IItemHandler handler, int slot) {
        this.handler = handler;
        this.slot = slot;
    }

    @Override
    public long insert(AEKey what, long amount, Actionable mode, IActionSource source) {
        if (!(what instanceof AEItemKey itemKey) || amount <= 0) {
            return 0;
        }
        var remainder = handler.insertItem(slot, itemKey.toStack(saturated(amount)), mode.isSimulate());
        return amount - remainder.getCount();
    }

    @Override
    public long extract(AEKey what, long amount, Actionable mode, IActionSource source) {
        if (!(what instanceof AEItemKey itemKey) || amount <= 0) {
            return 0;
        }
        if (!itemKey.matches(handler.getStackInSlot(slot))) {
            return 0;
        }
        return handler.extractItem(slot, saturated(amount), mode.isSimulate()).getCount();
    }

    @Override
    public void getAvailableStacks(KeyCounter out) {
        var stack = handler.getStackInSlot(slot);
        if (stack.isEmpty()) {
            return;
        }
        var key = AEItemKey.of(stack);
        if (key != null) {
            out.add(key, stack.getCount());
        }
    }

    @Override
    public Component getDescription() {
        return Component.translatable("gui.appliedfactory.slot_storage", slot);
    }

    private static int saturated(long amount) {
        return amount > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) amount;
    }
}
