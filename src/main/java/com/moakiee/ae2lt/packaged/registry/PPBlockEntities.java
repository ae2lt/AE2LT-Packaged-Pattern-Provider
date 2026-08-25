package com.moakiee.ae2lt.packaged.registry;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

import com.moakiee.ae2lt.packaged.AE2LTPackagedProvider;
import com.moakiee.ae2lt.packaged.blockentity.PackagedPatternProviderBlockEntity;
import com.moakiee.ae2lt.packaged.blockentity.WirelessPackagedPatternProviderBlockEntity;

public final class PPBlockEntities {
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITY_TYPES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, AE2LTPackagedProvider.MODID);

    public static final RegistryObject<BlockEntityType<PackagedPatternProviderBlockEntity>>
            PACKAGED_PATTERN_PROVIDER = BLOCK_ENTITY_TYPES.register(
                    "packaged_pattern_provider",
                    () -> BlockEntityType.Builder.of(
                            PackagedPatternProviderBlockEntity::new,
                            PPBlocks.PACKAGED_PATTERN_PROVIDER.get())
                            .build(null));

    public static final RegistryObject<BlockEntityType<WirelessPackagedPatternProviderBlockEntity>>
            WIRELESS_PACKAGED_PATTERN_PROVIDER = BLOCK_ENTITY_TYPES.register(
                    "wireless_packaged_pattern_provider",
                    () -> BlockEntityType.Builder.of(
                            WirelessPackagedPatternProviderBlockEntity::new,
                            PPBlocks.WIRELESS_PACKAGED_PATTERN_PROVIDER.get())
                            .build(null));

    private PPBlockEntities() {
    }
}
