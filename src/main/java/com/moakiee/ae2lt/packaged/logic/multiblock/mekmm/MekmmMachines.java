package com.moakiee.ae2lt.packaged.logic.multiblock.mekmm;

import static com.moakiee.ae2lt.packaged.logic.multiblock.mekmm.MekPortSpec.RelativeAccess.BACK;
import static com.moakiee.ae2lt.packaged.logic.multiblock.mekmm.MekPortSpec.RelativeAccess.FRONT;
import static com.moakiee.ae2lt.packaged.logic.multiblock.mekmm.MekPortSpec.RelativeAccess.LEFT;
import static com.moakiee.ae2lt.packaged.logic.multiblock.mekmm.MekPortSpec.RelativeAccess.RIGHT;
import static com.moakiee.ae2lt.packaged.logic.multiblock.mekmm.MekmmMachineSpec.PortType.CHEMICAL;
import static com.moakiee.ae2lt.packaged.logic.multiblock.mekmm.MekmmMachineSpec.PortType.FLUID;
import static com.moakiee.ae2lt.packaged.logic.multiblock.mekmm.MekmmMachineSpec.PortType.ITEM;

import java.lang.reflect.Field;
import java.util.List;
import java.util.function.ToIntFunction;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntity;

import com.moakiee.ae2lt.packaged.logic.multiblock.ReflectionSupport;
import com.moakiee.ae2lt.packaged.logic.multiblock.mekmm.MekmmMachineSpec.PortDef;
import com.moakiee.ae2lt.packaged.logic.multiblock.mekmm.MekmmMachineSpec.PortGroup;

/**
 * Catalog of every {@code mekmm} multiblock controller served by the unified
 * {@link MekanismMoreMachinesAdapter}.
 *
 * <p>Adding a new mekmm machine = appending one entry here; no new adapter
 * class, no new packaged core, no extra registration in {@code AE2LTPackagedProvider}.
 */
final class MekmmMachines {

    static final List<MekmmMachineSpec> ALL = List.of(
            nucleosynthesizer(),
            chemicalInfuser(),
            electrolyticSeparator(),
            rotaryCondensentrator(),
            solarNeutronActivator(),
            pigmentMixer()
    );

    private MekmmMachines() {}

    // === Large Antiprotonic Nucleosynthesizer (3×3×3) ===
    private static MekmmMachineSpec nucleosynthesizer() {
        return new MekmmMachineSpec(
                id("large_antiprotonic_nucleosynthesizer"),
                "com.jerry.meklm.common.tile.machine.TileEntityLargeAntiprotonicNucleosynthesizer",
                true,
                List.of(new PortGroup(
                        List.of(
                                new PortDef(new MekPortSpec(1, 0, 0, BACK), ITEM),
                                new PortDef(new MekPortSpec(1, 1, 2, BACK), CHEMICAL)
                        ),
                        List.of(new PortDef(new MekPortSpec(1, 0, 2, BACK), ITEM))
                )),
                null
        );
    }

    // === Large Chemical Infuser (3×3×2+top) ===
    private static MekmmMachineSpec chemicalInfuser() {
        return new MekmmMachineSpec(
                id("large_chemical_infuser"),
                "com.jerry.meklm.common.tile.machine.TileEntityLargeChemicalInfuser",
                true,
                List.of(new PortGroup(
                        List.of(
                                new PortDef(new MekPortSpec(1, 1, 0, LEFT), CHEMICAL),
                                new PortDef(new MekPortSpec(1, -1, 0, RIGHT), CHEMICAL)
                        ),
                        List.of(new PortDef(new MekPortSpec(-1, 1, 0, FRONT), CHEMICAL))
                )),
                null
        );
    }

    // === Large Electrolytic Separator (3×3×2) ===
    private static MekmmMachineSpec electrolyticSeparator() {
        return new MekmmMachineSpec(
                id("large_electrolytic_separator"),
                "com.jerry.meklm.common.tile.machine.TileEntityLargeElectrolyticSeparator",
                true,
                List.of(new PortGroup(
                        List.of(new PortDef(new MekPortSpec(1, 1, 0, BACK), FLUID)),
                        List.of(
                                new PortDef(new MekPortSpec(-1, 1, 0, FRONT), CHEMICAL),
                                new PortDef(new MekPortSpec(-1, -1, 0, FRONT), CHEMICAL)
                        )
                )),
                null
        );
    }

    // === Large Rotary Condensentrator (3×3×3, two modes) ===
    // mode field == false  → condensentrating (chemical → fluid) → group index 0
    // mode field == true   → decondensentrating (fluid → chemical) → group index 1
    private static MekmmMachineSpec rotaryCondensentrator() {
        return new MekmmMachineSpec(
                id("large_rotary_condensentrator"),
                "com.jerry.meklm.common.tile.machine.TileEntityLargeRotaryCondensentrator",
                true,
                List.of(
                        new PortGroup(
                                List.of(new PortDef(new MekPortSpec(1, 1, 0, BACK), CHEMICAL)),
                                List.of(new PortDef(new MekPortSpec(0, -1, 1, RIGHT), FLUID))
                        ),
                        new PortGroup(
                                List.of(new PortDef(new MekPortSpec(1, -1, 0, BACK), FLUID)),
                                List.of(new PortDef(new MekPortSpec(0, 1, 1, LEFT), CHEMICAL))
                        )
                ),
                rotaryModeSelector()
        );
    }

    private static ToIntFunction<BlockEntity> rotaryModeSelector() {
        return be -> {
            try {
                Field field = ReflectionSupport.findDeclaredFieldCached(be.getClass(), "mode").orElse(null);
                if (field == null) {
                    return 0;
                }
                return ((boolean) field.get(be)) ? 1 : 0;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                return 0;
            }
        };
    }

    // === Large Solar Neutron Activator (3×3×3, never idle-gates dispatch) ===
    private static MekmmMachineSpec solarNeutronActivator() {
        return new MekmmMachineSpec(
                id("large_solar_neutron_activator"),
                "com.jerry.meklm.common.tile.machine.TileEntityLargeSolarNeutronActivator",
                false,
                List.of(new PortGroup(
                        List.of(new PortDef(new MekPortSpec(1, 1, 0, LEFT), CHEMICAL)),
                        List.of(new PortDef(new MekPortSpec(1, -1, 0, BACK), CHEMICAL))
                )),
                null
        );
    }

    // === Large Pigment Mixer (3×3×2+2) ===
    private static MekmmMachineSpec pigmentMixer() {
        return new MekmmMachineSpec(
                id("large_pigment_mixer"),
                "com.jerry.meklm.common.tile.machine.TileEntityLargePigmentMixer",
                true,
                List.of(new PortGroup(
                        List.of(
                                new PortDef(new MekPortSpec(1, 1, 0, LEFT), CHEMICAL),
                                new PortDef(new MekPortSpec(1, -1, 0, RIGHT), CHEMICAL)
                        ),
                        List.of(new PortDef(new MekPortSpec(-1, 1, 0, FRONT), CHEMICAL))
                )),
                null
        );
    }

    private static ResourceLocation id(String path) {
        return new ResourceLocation("mekmm", path);
    }
}
