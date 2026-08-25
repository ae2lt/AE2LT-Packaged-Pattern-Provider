package com.moakiee.ae2lt.packaged;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import appeng.blockentity.AEBaseBlockEntity;

import com.moakiee.ae2lt.packaged.blockentity.PackagedPatternProviderBlockEntity;
import com.moakiee.ae2lt.packaged.blockentity.WirelessPackagedPatternProviderBlockEntity;
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
import com.moakiee.ae2lt.packaged.patternprovider.StablePatternProviderBlockEntity;

@Mod(AE2LTPackagedProvider.MODID)
public class AE2LTPackagedProvider {
    public static final String MODID = "ae2ltpp";
    private static final Logger LOGGER = LogUtils.getLogger();

    public AE2LTPackagedProvider() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();

        PPItems.ITEMS.register(modEventBus);
        PPBlocks.BLOCKS.register(modEventBus);
        PPBlockEntities.BLOCK_ENTITY_TYPES.register(modEventBus);
        PPMenuTypes.MENU_TYPES.register(modEventBus);
        PPCreativeTabs.CREATIVE_MODE_TABS.register(modEventBus);

        modEventBus.addListener(this::commonSetup);
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

    // Forge 1.20.1 has no block-entity capability-provider registration event.
    // AE2's IN_WORLD_GRID_NODE_HOST lookup falls back to an
    // `instanceof IInWorldGridNodeHost` check (GridHelper#getNodeHost) that
    // AENetworkBlockEntity already satisfies, and GENERIC_INTERNAL_INV is
    // exposed from StablePatternProviderBlockEntity#getCapability instead.
}
