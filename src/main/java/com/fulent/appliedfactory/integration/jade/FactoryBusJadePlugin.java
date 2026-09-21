package com.fulent.appliedfactory.integration.jade;

import java.util.ArrayList;
import java.util.HashMap;

import com.fulent.appliedfactory.part.FactoryBusPart;

import appeng.api.parts.IPartHost;
import appeng.block.networking.CableBusBlock;
import appeng.blockentity.networking.CableBusBlockEntity;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IComponentProvider;
import snownee.jade.api.IServerDataProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.IWailaClientRegistration;
import snownee.jade.api.IWailaCommonRegistration;
import snownee.jade.api.IWailaPlugin;
import snownee.jade.api.WailaPlugin;
import snownee.jade.api.config.IPluginConfig;
import snownee.jade.api.ui.IElement;
import snownee.jade.api.ui.IElementHelper;

import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/**
 * Jade-only enrichment of the Factory Bus tooltip: lists every item slot of the
 * target container with its item icon, using the same 0-based indices as
 * {@code bus.slot(n)}. Empty slots are shown as empty too.
 *
 * <p>AE2's cross-tooltip-mod abstraction can only emit text lines, so icons
 * need this Jade-specific component. It is discovered through Jade's
 * {@link WailaPlugin} annotation scan, so it stays dormant when Jade is absent.
 * WTHIT and The One Probe keep the plain textual list from
 * {@code FactoryBusTooltipProvider}.</p>
 */
@WailaPlugin("appliedfactory")
public final class FactoryBusJadePlugin implements IWailaPlugin {
    private static final ResourceLocation UID =
            ResourceLocation.fromNamespaceAndPath("appliedfactory", "factory_bus_slots");
    private static final String DATA_KEY = "appliedfactory:slots";
    private static final String TAG_COUNT = "count";
    private static final String TAG_ITEMS = "items";
    private static final String TAG_SLOT = "slot";
    private static final String TAG_STACK = "stack";
    private static final int MAX_SLOTS = 512;

    @Override
    public void register(IWailaCommonRegistration registration) {
        registration.registerBlockDataProvider(new BusSlotServerData(), CableBusBlockEntity.class);
    }

    @Override
    public void registerClient(IWailaClientRegistration registration) {
        registration.registerBlockComponent(new BusSlotComponent(), CableBusBlock.class);
    }

    private static FactoryBusPart selectedBus(BlockAccessor accessor) {
        var blockEntity = accessor.getBlockEntity();
        if (!(blockEntity instanceof IPartHost host)) {
            return null;
        }
        var selected = host.selectPartWorld(accessor.getHitResult().getLocation());
        return selected.part instanceof FactoryBusPart bus ? bus : null;
    }

    private static final class BusSlotServerData implements IServerDataProvider<BlockAccessor> {
        @Override
        public ResourceLocation getUid() {
            return UID;
        }

        @Override
        public void appendServerData(CompoundTag data, BlockAccessor accessor) {
            var part = selectedBus(accessor);
            var target = part == null ? null : part.target().orElse(null);
            var handler = target == null ? null : target.itemHandler();
            if (handler == null) {
                return;
            }
            var totalSlots = handler.getSlots();
            if (totalSlots <= 0) {
                return;
            }
            var registries = accessor.getPlayer().registryAccess();
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
                entry.put(TAG_STACK, stack.save(registries));
                items.add(entry);
            }
            root.put(TAG_ITEMS, items);
            data.put(DATA_KEY, root);
        }
    }

    private static final class BusSlotComponent implements IComponentProvider<BlockAccessor> {
        @Override
        public ResourceLocation getUid() {
            return UID;
        }

        @Override
        public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
            if (selectedBus(accessor) == null) {
                return;
            }
            var root = accessor.getServerData().getCompound(DATA_KEY);
            if (root.isEmpty()) {
                return;
            }
            var totalSlots = root.getInt(TAG_COUNT);
            if (totalSlots <= 0) {
                return;
            }
            var registries = accessor.getLevel().registryAccess();
            var stacks = new HashMap<Integer, ItemStack>();
            var items = root.getList(TAG_ITEMS, Tag.TAG_COMPOUND);
            for (var tag : items) {
                if (!(tag instanceof CompoundTag entry)) {
                    continue;
                }
                var stack = ItemStack.parseOptional(registries, entry.getCompound(TAG_STACK));
                if (!stack.isEmpty()) {
                    stacks.put(entry.getInt(TAG_SLOT), stack);
                }
            }

            var helper = IElementHelper.get();
            var visibleSlots = Math.min(totalSlots, MAX_SLOTS);
            tooltip.add(Component.translatable(
                    "gui.appliedfactory.bus_slots_header", visibleSlots)
                    .withStyle(ChatFormatting.GRAY));
            for (int slot = 0; slot < visibleSlots; slot++) {
                var stack = stacks.get(slot);
                var line = new ArrayList<IElement>(2);
                line.add(helper.text(Component.literal("#" + slot)
                        .withStyle(ChatFormatting.DARK_GRAY)));
                if (stack == null) {
                    line.add(helper.text(Component.translatable(
                            "gui.appliedfactory.bus_slot_empty_suffix")
                            .withStyle(ChatFormatting.DARK_GRAY)));
                } else {
                    line.add(helper.smallItem(stack));
                }
                tooltip.add(line);
            }
            if (totalSlots > visibleSlots) {
                tooltip.add(Component.translatable(
                        "gui.appliedfactory.bus_slots_truncated", totalSlots - visibleSlots)
                        .withStyle(ChatFormatting.DARK_GRAY));
            }
        }
    }
}
