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
 * <p>The selected face handler supplies local slot numbers, contents and transfer
 * permissions. An unsided handler is used only when the face exposes none.</p>
 */
final class FactorySlotStorage implements MEStorage {
    private final IItemHandler handler;
    private final int slot;
    private final boolean extractableOnly;

    FactorySlotStorage(IItemHandler handler, int slot, boolean extractableOnly) {
        this.handler = handler;
        this.slot = slot;
        this.extractableOnly = extractableOnly;
    }

    @Override
    public long insert(AEKey what, long amount, Actionable mode, IActionSource source) {
        if (!(what instanceof AEItemKey itemKey) || amount <= 0) {
            return 0;
        }
        var attempted = saturated(amount);
        var remainder = handler.insertItem(slot, itemKey.toStack(attempted), mode.isSimulate());
        return attempted - remainder.getCount();
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
            var amount = stack.getCount();
            if (extractableOnly) {
                var extracted = handler.extractItem(slot, amount, true);
                if (!key.matches(extracted)) {
                    return;
                }
                amount = Math.min(amount, extracted.getCount());
            }
            if (amount > 0) {
                out.add(key, amount);
            }
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
