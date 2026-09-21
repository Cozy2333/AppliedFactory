package com.fulent.appliedfactory.integration.igtooltip;

import java.util.HashMap;

import com.fulent.appliedfactory.part.FactoryBusPart;

import appeng.api.integrations.igtooltip.PartTooltips;
import appeng.api.integrations.igtooltip.TooltipBuilder;
import appeng.api.integrations.igtooltip.TooltipContext;
import appeng.api.integrations.igtooltip.providers.BodyProvider;
import appeng.api.integrations.igtooltip.providers.ServerDataProvider;

import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;

/**
 * Textual fallback that lists the target container's item slots and their
 * contents in the Factory Bus tooltip, using the same 0-based indices as
 * {@code bus.slot(n)}. Empty slots are shown too, so a script author can read
 * off the exact index to use.
 *
 * <p>Registered through AE2's {@link PartTooltips} abstraction, the same route
 * AE2 uses for its own parts, so it renders in every tooltip mod AE2 integrates
 * (WTHIT, The One Probe). When Jade is present it stands down in favour of
 * {@code FactoryBusJadePlugin}, which shows the same slots with item icons.</p>
 */
public final class FactoryBusTooltipProvider
        implements ServerDataProvider<FactoryBusPart>, BodyProvider<FactoryBusPart> {
    private static final String DATA_KEY = "appliedfactory:slots";
    private static final String TAG_COUNT = "count";
    private static final String TAG_ITEMS = "items";
    private static final String TAG_SLOT = "slot";
    private static final String TAG_STACK = "stack";
    /** Guards against pathological inventories producing enormous tooltips/packets. */
    private static final int MAX_SLOTS = 512;

    private FactoryBusTooltipProvider() {
    }

    /** Called once during mod construction on both sides. */
    public static void register() {
        var provider = new FactoryBusTooltipProvider();
        PartTooltips.addServerData(FactoryBusPart.class, provider);
        PartTooltips.addBody(FactoryBusPart.class, provider);
    }

    @Override
    public void provideServerData(Player player, FactoryBusPart part, CompoundTag serverData) {
        var target = part.target().orElse(null);
        if (target == null) {
            return;
        }
        var handler = target.itemHandler();
        if (handler == null) {
            return;
        }
        var totalSlots = handler.getSlots();
        if (totalSlots <= 0) {
            return;
        }
        var visibleSlots = Math.min(totalSlots, MAX_SLOTS);
        var root = new CompoundTag();
        root.putInt(TAG_COUNT, totalSlots);
        var items = new ListTag();
        for (int slot = 0; slot < visibleSlots; slot++) {
            var stack = handler.getStackInSlot(slot);
            if (stack.isEmpty()) {
                continue;
            }
            var entry = new CompoundTag();
            entry.putInt(TAG_SLOT, slot);
            entry.put(TAG_STACK, stack.save(player.registryAccess()));
            items.add(entry);
        }
        root.put(TAG_ITEMS, items);
        serverData.put(DATA_KEY, root);
    }

    @Override
    public void buildTooltip(FactoryBusPart part, TooltipContext context, TooltipBuilder tooltip) {
        // Jade shows the same slots as item icons instead (FactoryBusJadePlugin);
        // this textual fallback is for WTHIT / The One Probe.
        if (ModList.get().isLoaded("jade")) {
            return;
        }
        var root = context.serverData().getCompound(DATA_KEY);
        if (root.isEmpty()) {
            return;
        }
        var totalSlots = root.getInt(TAG_COUNT);
        if (totalSlots <= 0) {
            return;
        }
        var stacks = new HashMap<Integer, ItemStack>();
        var items = root.getList(TAG_ITEMS, Tag.TAG_COMPOUND);
        for (var tag : items) {
            if (!(tag instanceof CompoundTag entry)) {
                continue;
            }
            var stack = ItemStack.parseOptional(
                    context.registries(), entry.getCompound(TAG_STACK));
            if (!stack.isEmpty()) {
                stacks.put(entry.getInt(TAG_SLOT), stack);
            }
        }

        var visibleSlots = Math.min(totalSlots, MAX_SLOTS);
        tooltip.addLine(Component.translatable(
                "gui.appliedfactory.bus_slots_header", visibleSlots)
                .withStyle(ChatFormatting.GRAY));
        for (int slot = 0; slot < visibleSlots; slot++) {
            var stack = stacks.get(slot);
            if (stack == null) {
                tooltip.addLine(Component.translatable(
                        "gui.appliedfactory.bus_slot_empty", slot)
                        .withStyle(ChatFormatting.DARK_GRAY));
            } else {
                tooltip.addLine(Component.translatable(
                        "gui.appliedfactory.bus_slot_entry", slot,
                        stack.getHoverName().copy().withStyle(ChatFormatting.WHITE),
                        stack.getCount())
                        .withStyle(ChatFormatting.GRAY));
            }
        }
        if (totalSlots > visibleSlots) {
            tooltip.addLine(Component.translatable(
                    "gui.appliedfactory.bus_slots_truncated", totalSlots - visibleSlots)
                    .withStyle(ChatFormatting.DARK_GRAY));
        }
    }
}
