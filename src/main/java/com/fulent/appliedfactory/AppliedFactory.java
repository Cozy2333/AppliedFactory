package com.fulent.appliedfactory;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import com.fulent.appliedfactory.block.FactoryControllerBlock;
import com.fulent.appliedfactory.blockentity.FactoryControllerBlockEntity;
import com.fulent.appliedfactory.factory.McpProbeManager;
import com.fulent.appliedfactory.integration.igtooltip.FactoryBusTooltipProvider;
import com.fulent.appliedfactory.item.FactoryBusItem;
import com.fulent.appliedfactory.menu.FactoryControllerProgramMenu;
import com.fulent.appliedfactory.network.NetworkHandler;
import com.fulent.appliedfactory.part.FactoryBusPart;
import com.fulent.appliedfactory.script.ControllerProgramComponent;

import appeng.api.AECapabilities;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

@Mod(AppliedFactory.MOD_ID)
public final class AppliedFactory {
    public static final String MOD_ID = "appliedfactory";
    public static final Logger LOGGER = LogUtils.getLogger();

    // 方块注册器
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MOD_ID);
    // 物品注册器
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MOD_ID);
    // 方块实体注册器
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITY_TYPES = DeferredRegister
            .create(Registries.BLOCK_ENTITY_TYPE, MOD_ID);
    // 菜单注册器
    public static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(Registries.MENU, MOD_ID);
    // 数据组件注册器
    public static final DeferredRegister<DataComponentType<?>> DATA_COMPONENT_TYPES = DeferredRegister
            .create(Registries.DATA_COMPONENT_TYPE, MOD_ID);
    // 创造模式标签页注册器
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS = DeferredRegister
            .create(Registries.CREATIVE_MODE_TAB, MOD_ID);

    // 注册方块：原石硬度，需要镐采集
    public static final DeferredBlock<Block> FACTORY_CONTROLLER = BLOCKS.register("factory_controller",
            () -> new FactoryControllerBlock(BlockBehaviour.Properties.of()
                    .strength(1.5F, 6.0F)
                    .requiresCorrectToolForDrops()));
// 方块物品
    public static final DeferredItem<BlockItem> FACTORY_CONTROLLER_ITEM = ITEMS
            .registerSimpleBlockItem("factory_controller", FACTORY_CONTROLLER);
// 工厂总线物品
    public static final DeferredItem<FactoryBusItem> FACTORY_BUS_ITEM = ITEMS.register("factory_bus",
            () -> new FactoryBusItem(new Item.Properties()));
// 独立创造物品页
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> FACTORY_TAB = CREATIVE_MODE_TABS
            .register("applied_factory", () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.appliedfactory"))
                    .icon(() -> new ItemStack(FACTORY_CONTROLLER_ITEM.get()))
                    .displayItems((parameters, output) -> {
                        output.accept(FACTORY_CONTROLLER_ITEM);
                        output.accept(FACTORY_BUS_ITEM);
                    })
                    .build());
// 方块实体
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<FactoryControllerBlockEntity>> FACTORY_CONTROLLER_BLOCK_ENTITY = BLOCK_ENTITY_TYPES
            .register("factory_controller", () -> BlockEntityType.Builder
                    .of(FactoryControllerBlockEntity::new, FACTORY_CONTROLLER.get()).build(null));
// 控制器面板
// 代码面板
    public static final DeferredHolder<MenuType<?>, MenuType<FactoryControllerProgramMenu>> FACTORY_CONTROLLER_PROGRAM_MENU = MENUS
            .register("factory_controller_program",
                    () -> IMenuTypeExtension.create(FactoryControllerProgramMenu::new));
    // 控制器程序数据组件：掉落/放置时随物品携带内部程序
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<ControllerProgramComponent>> CONTROLLER_PROGRAM_COMPONENT =
            DATA_COMPONENT_TYPES.register("controller_program",
                    () -> DataComponentType.<ControllerProgramComponent>builder()
                            .persistent(ControllerProgramComponent.CODEC)
                            .networkSynchronized(ControllerProgramComponent.STREAM_CODEC)
                            .build());
    public AppliedFactory(IEventBus modEventBus, ModContainer modContainer) {
        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
        BLOCK_ENTITY_TYPES.register(modEventBus);
        MENUS.register(modEventBus);
        DATA_COMPONENT_TYPES.register(modEventBus);
        CREATIVE_MODE_TABS.register(modEventBus);

        modEventBus.addListener(this::registerCapabilities);
        
        modEventBus.addListener(NetworkHandler::register);
        
        FactoryBusPart.registerModels();
        FactoryBusTooltipProvider.register();

        McpProbeManager.register();
    }

    private void registerCapabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(AECapabilities.IN_WORLD_GRID_NODE_HOST,
                FACTORY_CONTROLLER_BLOCK_ENTITY.get(), (factory, context) -> factory);
    }
}
