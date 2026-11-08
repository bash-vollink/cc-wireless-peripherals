package dev.ccwp;

import dev.ccwp.item.PeripheralLinkerItem;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModRegistry {
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(CCWirelessPeripherals.MOD_ID);
    private static final DeferredRegister.DataComponents COMPONENTS =
        DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, CCWirelessPeripherals.MOD_ID);

    /** The host selected by a Peripheral Linker. */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<GlobalPos>> SELECTED_HOST =
        COMPONENTS.registerComponentType("selected_host", b -> b.persistent(GlobalPos.CODEC).networkSynchronized(GlobalPos.STREAM_CODEC));

    public static final DeferredItem<PeripheralLinkerItem> PERIPHERAL_LINKER =
        ITEMS.register("peripheral_linker", () -> new PeripheralLinkerItem(new Item.Properties().stacksTo(1)));

    private ModRegistry() {
    }

    static void register(IEventBus bus) {
        ITEMS.register(bus);
        COMPONENTS.register(bus);
        bus.addListener(ModRegistry::addCreative);
    }

    private static void addCreative(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) event.accept(PERIPHERAL_LINKER.get());
    }
}
