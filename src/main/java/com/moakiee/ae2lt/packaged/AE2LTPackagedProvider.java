package com.moakiee.ae2lt.packaged;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import appeng.api.AECapabilities;
import appeng.api.networking.IInWorldGridNodeHost;
import appeng.blockentity.AEBaseBlockEntity;

import com.moakiee.ae2lt.packaged.blockentity.PackagedPatternProviderBlockEntity;
import com.moakiee.ae2lt.packaged.blockentity.WirelessPackagedPatternProviderBlockEntity;
import com.moakiee.ae2lt.packaged.logic.PackagedPatternProviderLogic;
import com.moakiee.ae2lt.packaged.logic.multiblock.MultiblockAdapterRegistry;
import com.moakiee.ae2lt.packaged.logic.multiblock.aa.ActuallyAdditionsAtomicReconstructorAdapter;
import com.moakiee.ae2lt.packaged.logic.multiblock.aa.ActuallyAdditionsEmpowererAdapter;
import com.moakiee.ae2lt.packaged.logic.multiblock.ars.ArsNouveauEnchantingApparatusAdapter;
import com.moakiee.ae2lt.packaged.logic.multiblock.ars.ArsNouveauImbuementChamberAdapter;
import com.moakiee.ae2lt.packaged.logic.multiblock.avaritia.AvaritiaExtremeSmithingAdapter;
import com.moakiee.ae2lt.packaged.logic.multiblock.avaritia.AvaritiaTableAdapter;
import com.moakiee.ae2lt.packaged.logic.multiblock.botania.AlfheimPortalAdapter;
import com.moakiee.ae2lt.packaged.logic.multiblock.botania.ManaPoolAdapter;
import com.moakiee.ae2lt.packaged.logic.multiblock.botania.PetalApothecaryAdapter;
import com.moakiee.ae2lt.packaged.logic.multiblock.botania.RunicAltarAdapter;
import com.moakiee.ae2lt.packaged.logic.multiblock.botania.TerraPlateAdapter;
import com.moakiee.ae2lt.packaged.logic.multiblock.de.DraconicFusionCraftingAdapter;
import com.moakiee.ae2lt.packaged.logic.multiblock.ec.ExtendedCraftingCombinationAdapter;
import com.moakiee.ae2lt.packaged.logic.multiblock.ec.ExtendedCraftingEnderCrafterAdapter;
import com.moakiee.ae2lt.packaged.logic.multiblock.ec.ExtendedCraftingFluxCrafterAdapter;
import com.moakiee.ae2lt.packaged.logic.multiblock.ec.ExtendedCraftingTableAdapter;
import com.moakiee.ae2lt.packaged.logic.multiblock.ma.AwakeningAltarAdapter;
import com.moakiee.ae2lt.packaged.logic.multiblock.ma.InfusionAltarAdapter;
import com.moakiee.ae2lt.packaged.logic.multiblock.malum.MalumSpiritFocusingAdapter;
import com.moakiee.ae2lt.packaged.logic.multiblock.malum.MalumSpiritInfusionAdapter;
import com.moakiee.ae2lt.packaged.logic.multiblock.occultism.OccultismRitualAdapter;
import com.moakiee.ae2lt.packaged.logic.multiblock.occultism.OccultismSpiritFireAdapter;
import com.moakiee.ae2lt.packaged.logic.multiblock.mekmm.MekanismMoreMachinesAdapter;
import com.moakiee.ae2lt.packaged.registry.PPBlockEntities;
import com.moakiee.ae2lt.packaged.registry.PPBlocks;
import com.moakiee.ae2lt.packaged.registry.PPCreativeTabs;
import com.moakiee.ae2lt.packaged.registry.PPItems;
import com.moakiee.ae2lt.packaged.registry.PPMenuTypes;
import com.moakiee.ae2lt.packaged.patternprovider.InsertOnlyPatternProviderReturnInventory;
import com.moakiee.ae2lt.packaged.patternprovider.StablePatternProviderBlockEntity;

@Mod(AE2LTPackagedProvider.MODID)
public class AE2LTPackagedProvider {
    public static final String MODID = "ae2ltpp";
    private static final Logger LOGGER = LogUtils.getLogger();

    public AE2LTPackagedProvider(IEventBus modEventBus, ModContainer modContainer) {
        PPItems.ITEMS.register(modEventBus);
        PPBlocks.BLOCKS.register(modEventBus);
        PPBlockEntities.BLOCK_ENTITY_TYPES.register(modEventBus);
        PPMenuTypes.MENU_TYPES.register(modEventBus);
        PPCreativeTabs.CREATIVE_MODE_TABS.register(modEventBus);

        modEventBus.addListener(this::commonSetup);
        modEventBus.addListener(this::registerCapabilities);
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            MultiblockAdapterRegistry.register(new ActuallyAdditionsAtomicReconstructorAdapter());
            MultiblockAdapterRegistry.register(new ActuallyAdditionsEmpowererAdapter());
            MultiblockAdapterRegistry.register(new ArsNouveauEnchantingApparatusAdapter());
            MultiblockAdapterRegistry.register(new ArsNouveauImbuementChamberAdapter());
            MultiblockAdapterRegistry.register(new DraconicFusionCraftingAdapter());
            MultiblockAdapterRegistry.register(new ExtendedCraftingTableAdapter());
            MultiblockAdapterRegistry.register(new ExtendedCraftingEnderCrafterAdapter());
            MultiblockAdapterRegistry.register(new ExtendedCraftingFluxCrafterAdapter());
            MultiblockAdapterRegistry.register(new ExtendedCraftingCombinationAdapter());
            MultiblockAdapterRegistry.register(new AvaritiaTableAdapter());
            MultiblockAdapterRegistry.register(new AvaritiaExtremeSmithingAdapter());
            MultiblockAdapterRegistry.register(new OccultismRitualAdapter());
            MultiblockAdapterRegistry.register(new OccultismSpiritFireAdapter());
            MultiblockAdapterRegistry.register(new InfusionAltarAdapter());
            MultiblockAdapterRegistry.register(new AwakeningAltarAdapter());
            MultiblockAdapterRegistry.register(new MekanismMoreMachinesAdapter());
            MultiblockAdapterRegistry.register(new MalumSpiritFocusingAdapter());
            MultiblockAdapterRegistry.register(new MalumSpiritInfusionAdapter());
            MultiblockAdapterRegistry.register(new PetalApothecaryAdapter());
            MultiblockAdapterRegistry.register(new ManaPoolAdapter());
            MultiblockAdapterRegistry.register(new AlfheimPortalAdapter());
            MultiblockAdapterRegistry.register(new TerraPlateAdapter());
            MultiblockAdapterRegistry.register(new RunicAltarAdapter());

            var packagedBlock = PPBlocks.PACKAGED_PATTERN_PROVIDER.get();
            var packagedBeType = PPBlockEntities.PACKAGED_PATTERN_PROVIDER.get();
            packagedBlock.setBlockEntity(
                    PackagedPatternProviderBlockEntity.class,
                    packagedBeType,
                    null,
                    StablePatternProviderBlockEntity::serverTick);
            AEBaseBlockEntity.registerBlockEntityItem(packagedBeType, packagedBlock.asItem());

            var wirelessPackagedBlock = PPBlocks.WIRELESS_PACKAGED_PATTERN_PROVIDER.get();
            var wirelessPackagedBeType = PPBlockEntities.WIRELESS_PACKAGED_PATTERN_PROVIDER.get();
            wirelessPackagedBlock.setBlockEntity(
                    WirelessPackagedPatternProviderBlockEntity.class,
                    wirelessPackagedBeType,
                    null,
                    StablePatternProviderBlockEntity::serverTick);
            AEBaseBlockEntity.registerBlockEntityItem(
                    wirelessPackagedBeType,
                    wirelessPackagedBlock.asItem());

            LOGGER.info("AE2LT Packaged Pattern Provider initialized");
        });
    }

    private void registerCapabilities(RegisterCapabilitiesEvent event) {
        registerGridNodeHost(event, PPBlockEntities.PACKAGED_PATTERN_PROVIDER.get());
        registerGridNodeHost(event, PPBlockEntities.WIRELESS_PACKAGED_PATTERN_PROVIDER.get());

        event.registerBlock(
                AECapabilities.GENERIC_INTERNAL_INV,
                (level, pos, state, blockEntity, context) -> {
                    if (blockEntity instanceof PackagedPatternProviderBlockEntity be
                            && be.getLogic() instanceof PackagedPatternProviderLogic logic) {
                        return new InsertOnlyPatternProviderReturnInventory(
                                logic.getInternalReturnInv(),
                                logic);
                    }
                    return null;
                },
                PPBlocks.PACKAGED_PATTERN_PROVIDER.get(),
                PPBlocks.WIRELESS_PACKAGED_PATTERN_PROVIDER.get());
    }

    private static void registerGridNodeHost(
            RegisterCapabilitiesEvent event,
            BlockEntityType<? extends StablePatternProviderBlockEntity> type) {
        event.registerBlockEntity(
                AECapabilities.IN_WORLD_GRID_NODE_HOST,
                type,
                (blockEntity, context) -> (IInWorldGridNodeHost) blockEntity);
    }

}
