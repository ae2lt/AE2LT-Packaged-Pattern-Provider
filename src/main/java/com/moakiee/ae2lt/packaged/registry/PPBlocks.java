package com.moakiee.ae2lt.packaged.registry;

import java.util.function.Supplier;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

import com.moakiee.ae2lt.packaged.AE2LTPackagedProvider;
import com.moakiee.ae2lt.packaged.block.PackagedPatternProviderBlock;
import com.moakiee.ae2lt.packaged.block.WirelessPackagedPatternProviderBlock;

public final class PPBlocks {
    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(Registries.BLOCK, AE2LTPackagedProvider.MODID);

    public static final RegistryObject<PackagedPatternProviderBlock> PACKAGED_PATTERN_PROVIDER =
            registerBlock("packaged_pattern_provider", PackagedPatternProviderBlock::new);

    public static final RegistryObject<WirelessPackagedPatternProviderBlock> WIRELESS_PACKAGED_PATTERN_PROVIDER =
            registerBlock("wireless_packaged_pattern_provider", WirelessPackagedPatternProviderBlock::new);

    private PPBlocks() {
    }

    private static <T extends Block> RegistryObject<T> registerBlock(String name, Supplier<T> blockFactory) {
        var registered = BLOCKS.register(name, blockFactory);
        PPItems.ITEMS.register(name, () -> new BlockItem(registered.get(), new Item.Properties()));
        return registered;
    }
}
