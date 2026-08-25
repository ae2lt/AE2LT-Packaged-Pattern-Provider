package com.moakiee.ae2lt.packaged.registry;

import java.util.LinkedHashSet;
import java.util.Set;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

import com.moakiee.ae2lt.packaged.AE2LTPackagedProvider;
import com.moakiee.ae2lt.packaged.item.AdapterIds;
import com.moakiee.ae2lt.packaged.item.MultiblockAdapterItem;
import com.moakiee.ae2lt.packaged.item.PackagedCoreBaseItem;

public final class PPItems {
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(Registries.ITEM, AE2LTPackagedProvider.MODID);

    // ===== Packaged cores (1 per slot, max-stack 1) =====
    //
    // Each provider holds exactly one core and is locked to that machine family.
    // EC tiers form a covers-down chain (ultimate ⊇ elite ⊇ advanced ⊇ basic) so
    // higher cores work on lower tables, matching the user's "向下兼容" requirement.

    public static final RegistryObject<MultiblockAdapterItem> AA_RECONSTRUCTOR_ADAPTER =
            registerPackagedCore("aa_reconstructor_packaged_core",
                    AdapterIds.AA_RECONSTRUCTOR,
                    Set.of(AdapterIds.AA_RECONSTRUCTOR));

    public static final RegistryObject<MultiblockAdapterItem> AA_EMPOWERER_ADAPTER =
            registerPackagedCore("aa_empowerer_packaged_core",
                    AdapterIds.AA_EMPOWERER,
                    Set.of(AdapterIds.AA_EMPOWERER));

    public static final RegistryObject<MultiblockAdapterItem> ARS_APPARATUS_ADAPTER =
            registerPackagedCore("ars_apparatus_packaged_core",
                    AdapterIds.ARS_APPARATUS,
                    Set.of(AdapterIds.ARS_APPARATUS));

    public static final RegistryObject<MultiblockAdapterItem> ARS_IMBUEMENT_ADAPTER =
            registerPackagedCore("ars_imbuement_packaged_core",
                    AdapterIds.ARS_IMBUEMENT,
                    Set.of(AdapterIds.ARS_IMBUEMENT));

    public static final RegistryObject<MultiblockAdapterItem> DE_FUSION_ADAPTER =
            registerPackagedCore("de_fusion_packaged_core",
                    AdapterIds.DE_FUSION,
                    Set.of(AdapterIds.DE_FUSION));

    public static final RegistryObject<MultiblockAdapterItem> EC_BASIC_ADAPTER =
            registerPackagedCore("ec_basic_table_packaged_core",
                    AdapterIds.EC_BASIC,
                    Set.of(AdapterIds.EC_BASIC));

    public static final RegistryObject<MultiblockAdapterItem> EC_ADVANCED_ADAPTER =
            registerPackagedCore("ec_advanced_table_packaged_core",
                    AdapterIds.EC_ADVANCED,
                    chain(AdapterIds.EC_BASIC, AdapterIds.EC_ADVANCED));

    public static final RegistryObject<MultiblockAdapterItem> EC_ELITE_ADAPTER =
            registerPackagedCore("ec_elite_table_packaged_core",
                    AdapterIds.EC_ELITE,
                    chain(AdapterIds.EC_BASIC, AdapterIds.EC_ADVANCED, AdapterIds.EC_ELITE));

    public static final RegistryObject<MultiblockAdapterItem> EC_ULTIMATE_ADAPTER =
            registerPackagedCore("ec_ultimate_table_packaged_core",
                    AdapterIds.EC_ULTIMATE,
                    chain(AdapterIds.EC_BASIC, AdapterIds.EC_ADVANCED,
                            AdapterIds.EC_ELITE, AdapterIds.EC_ULTIMATE));

    public static final RegistryObject<MultiblockAdapterItem> EC_ENDER_CRAFTER_ADAPTER =
            registerPackagedCore("ec_ender_crafter_packaged_core",
                    AdapterIds.EC_ENDER,
                    Set.of(AdapterIds.EC_ENDER));

    public static final RegistryObject<MultiblockAdapterItem> EC_FLUX_CRAFTER_ADAPTER =
            registerPackagedCore("ec_flux_crafter_packaged_core",
                    AdapterIds.EC_FLUX,
                    Set.of(AdapterIds.EC_FLUX));

    public static final RegistryObject<MultiblockAdapterItem> EC_COMBINATION_ADAPTER =
            registerPackagedCore("ec_combination_packaged_core",
                    AdapterIds.EC_COMBINATION,
                    Set.of(AdapterIds.EC_COMBINATION));

    public static final RegistryObject<MultiblockAdapterItem> MA_AWAKENING_ADAPTER =
            registerPackagedCore("ma_awakening_altar_packaged_core",
                    AdapterIds.MA_AWAKENING,
                    Set.of(AdapterIds.MA_AWAKENING));

    public static final RegistryObject<MultiblockAdapterItem> MA_INFUSION_ADAPTER =
            registerPackagedCore("ma_infusion_altar_packaged_core",
                    AdapterIds.MA_INFUSION,
                    Set.of(AdapterIds.MA_INFUSION));

    public static final RegistryObject<MultiblockAdapterItem> MALUM_SPIRIT_FOCUSING_ADAPTER =
            registerPackagedCore("malum_spirit_focusing_packaged_core",
                    AdapterIds.MALUM_SPIRIT_FOCUSING,
                    Set.of(AdapterIds.MALUM_SPIRIT_FOCUSING));

    public static final RegistryObject<MultiblockAdapterItem> MALUM_SPIRIT_INFUSION_ADAPTER =
            registerPackagedCore("malum_spirit_infusion_packaged_core",
                    AdapterIds.MALUM_SPIRIT_INFUSION,
                    Set.of(AdapterIds.MALUM_SPIRIT_INFUSION));

    public static final RegistryObject<MultiblockAdapterItem> OCCULTISM_RITUAL_ADAPTER =
            registerPackagedCore("occultism_ritual_packaged_core",
                    AdapterIds.OCCULTISM_RITUAL,
                    Set.of(AdapterIds.OCCULTISM_RITUAL));

    public static final RegistryObject<MultiblockAdapterItem> OCCULTISM_SPIRIT_FIRE_ADAPTER =
            registerPackagedCore("occultism_spirit_fire_packaged_core",
                    AdapterIds.OCCULTISM_SPIRIT_FIRE,
                    Set.of(AdapterIds.OCCULTISM_SPIRIT_FIRE));

    public static final RegistryObject<MultiblockAdapterItem> MEKMM_ADAPTER =
            registerPackagedCore("mekmm_packaged_core",
                    AdapterIds.MEKMM,
                    Set.of(AdapterIds.MEKMM));

    public static final RegistryObject<MultiblockAdapterItem> BOTANIA_PETAL_APOTHECARY_ADAPTER =
            registerPackagedCore("botania_petal_apothecary_packaged_core",
                    AdapterIds.BOTANIA_PETAL_APOTHECARY,
                    Set.of(AdapterIds.BOTANIA_PETAL_APOTHECARY));

    public static final RegistryObject<MultiblockAdapterItem> BOTANIA_MANA_POOL_ADAPTER =
            registerPackagedCore("botania_mana_pool_packaged_core",
                    AdapterIds.BOTANIA_MANA_POOL,
                    Set.of(AdapterIds.BOTANIA_MANA_POOL));

    public static final RegistryObject<MultiblockAdapterItem> BOTANIA_ALFHEIM_PORTAL_ADAPTER =
            registerPackagedCore("botania_alfheim_portal_packaged_core",
                    AdapterIds.BOTANIA_ALFHEIM_PORTAL,
                    Set.of(AdapterIds.BOTANIA_ALFHEIM_PORTAL));

    public static final RegistryObject<MultiblockAdapterItem> BOTANIA_TERRA_PLATE_ADAPTER =
            registerPackagedCore("botania_terra_plate_packaged_core",
                    AdapterIds.BOTANIA_TERRA_PLATE,
                    Set.of(AdapterIds.BOTANIA_TERRA_PLATE));

    public static final RegistryObject<MultiblockAdapterItem> BOTANIA_RUNIC_ALTAR_ADAPTER =
            registerPackagedCore("botania_runic_altar_packaged_core",
                    AdapterIds.BOTANIA_RUNIC_ALTAR,
                    Set.of(AdapterIds.BOTANIA_RUNIC_ALTAR));

    public static final RegistryObject<MultiblockAdapterItem> AVARITIA_SCULK_TABLE_ADAPTER =
            registerPackagedCore("avaritia_sculk_table_packaged_core",
                    AdapterIds.AVARITIA_SCULK_TABLE,
                    Set.of(AdapterIds.AVARITIA_SCULK_TABLE));

    public static final RegistryObject<MultiblockAdapterItem> AVARITIA_NETHER_TABLE_ADAPTER =
            registerPackagedCore("avaritia_nether_table_packaged_core",
                    AdapterIds.AVARITIA_NETHER_TABLE,
                    chain(AdapterIds.AVARITIA_SCULK_TABLE, AdapterIds.AVARITIA_NETHER_TABLE));

    public static final RegistryObject<MultiblockAdapterItem> AVARITIA_END_TABLE_ADAPTER =
            registerPackagedCore("avaritia_end_table_packaged_core",
                    AdapterIds.AVARITIA_END_TABLE,
                    chain(AdapterIds.AVARITIA_SCULK_TABLE, AdapterIds.AVARITIA_NETHER_TABLE,
                            AdapterIds.AVARITIA_END_TABLE));

    public static final RegistryObject<MultiblockAdapterItem> AVARITIA_EXTREME_TABLE_ADAPTER =
            registerPackagedCore("avaritia_extreme_table_packaged_core",
                    AdapterIds.AVARITIA_EXTREME_TABLE,
                    chain(AdapterIds.AVARITIA_SCULK_TABLE, AdapterIds.AVARITIA_NETHER_TABLE,
                            AdapterIds.AVARITIA_END_TABLE, AdapterIds.AVARITIA_EXTREME_TABLE));

    public static final RegistryObject<MultiblockAdapterItem> AVARITIA_EXTREME_SMITHING_ADAPTER =
            registerPackagedCore("avaritia_extreme_smithing_packaged_core",
                    AdapterIds.AVARITIA_EXTREME_SMITHING,
                    Set.of(AdapterIds.AVARITIA_EXTREME_SMITHING));

    // ===== Crafting intermediates =====

    /** Generic crafting base for the per-machine packaged cores. */
    public static final RegistryObject<Item> BASIC_PACKAGED_CORE =
            registerSimpleItem("basic_packaged_core", new Item.Properties());

    private PPItems() {
    }

    /**
     * Registers a plain packaged-core item.
     *
     * <p>NeoForge's {@code DeferredRegister.Items} ships a
     * {@code registerSimpleItem} convenience; Forge 1.20.1's plain
     * {@code DeferredRegister} does not, so the same shape is kept here.
     */
    private static RegistryObject<Item> registerSimpleItem(String name, Item.Properties props) {
        return ITEMS.register(name, () -> new PackagedCoreBaseItem(props));
    }

    private static RegistryObject<MultiblockAdapterItem> registerPackagedCore(
            String name,
            ResourceLocation primaryId,
            Set<ResourceLocation> coveredIds) {
        return ITEMS.register(name,
                () -> new MultiblockAdapterItem(
                        new Item.Properties().stacksTo(1),
                        primaryId,
                        coveredIds));
    }

    /** Build an insertion-ordered set (deterministic NBT-stable ordering). */
    private static Set<ResourceLocation> chain(ResourceLocation... ids) {
        var set = new LinkedHashSet<ResourceLocation>(ids.length);
        for (var id : ids) {
            set.add(id);
        }
        return set;
    }
}
