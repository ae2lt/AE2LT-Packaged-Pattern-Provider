package com.moakiee.ae2lt.packaged.logic.multiblock.mekmm;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import com.mojang.logging.LogUtils;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.common.capabilities.Capability;

import com.moakiee.ae2lt.packaged.logic.multiblock.BlockCapabilities;

import com.moakiee.ae2lt.packaged.logic.multiblock.ReflectionSupport;

final class MekReflection {

    private static final Logger LOG = LogUtils.getLogger();

    private static final String MEKMM_MOD_ID = "mekmm";
    private static final String MEKANISM_MOD_ID = "mekanism";

    private static volatile boolean lookupDone;
    private static volatile @Nullable Class<?> tileEntityMekanismClass;
    private static volatile @Nullable Class<?> boundingBlockClass;
    private static volatile @Nullable Method isActiveMethod;
    private static volatile @Nullable Method getMainPosMethod;
    private static volatile @Nullable Object chemicalBlockCapability;
    private static volatile @Nullable Class<?> chemicalHandlerClass;
    private static volatile @Nullable Class<?> chemicalStackClass;
    private static volatile @Nullable Class<?> actionClass;
    private static volatile @Nullable Object actionSimulate;
    private static volatile @Nullable Object actionExecute;
    private static volatile @Nullable Method insertChemicalMethod;
    private static volatile @Nullable Method extractChemicalMethod;
    private static volatile @Nullable Method getChemicalInTankMethod;
    private static volatile @Nullable Method getChemicalTanksMethod;
    private static volatile @Nullable Method chemicalGetAmountMethod;
    private static volatile @Nullable Method chemicalIsEmptyMethod;
    private static volatile @Nullable Method chemicalCopyWithAmountMethod;

    private MekReflection() {}

    static boolean isModLoaded() {
        return ModList.get().isLoaded(MEKMM_MOD_ID);
    }

    static boolean isMekanismLoaded() {
        return ModList.get().isLoaded(MEKANISM_MOD_ID);
    }

    static boolean isTileEntityClass(Object o, String className) {
        ensureLookup();
        return ReflectionSupport.findClassCached(className)
                .map(clazz -> clazz.isInstance(o))
                .orElse(false);
    }

    static boolean isBoundingBlock(BlockEntity be) {
        ensureLookup();
        return boundingBlockClass != null && boundingBlockClass.isInstance(be);
    }

    @Nullable
    static BlockPos getMainPos(BlockEntity boundingBe) {
        ensureLookup();
        if (getMainPosMethod == null || !isBoundingBlock(boundingBe)) return null;
        try {
            return (BlockPos) getMainPosMethod.invoke(boundingBe);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    static boolean isActive(BlockEntity be) {
        ensureLookup();
        if (isActiveMethod == null || tileEntityMekanismClass == null) return false;
        if (!tileEntityMekanismClass.isInstance(be)) return false;
        try {
            return (boolean) isActiveMethod.invoke(be);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    static Direction getFacing(BlockEntity be) {
        BlockState state = be.getBlockState();
        for (var prop : state.getProperties()) {
            if (prop instanceof DirectionProperty dp && prop.getName().equals("facing")) {
                return state.getValue(dp);
            }
        }
        return Direction.NORTH;
    }

    @SuppressWarnings("unchecked")
    @Nullable
    static <T> T getChemicalHandler(ServerLevel level, BlockPos pos, Direction side) {
        ensureLookup();
        if (chemicalBlockCapability == null) {
            LOG.debug("[ae2ltpp] getChemicalHandler: chemicalBlockCapability is null");
            return null;
        }
        try {
            var cap = (Capability<T>) chemicalBlockCapability;
            T result = BlockCapabilities.find(level, pos, cap, side);
            if (result == null) {
                LOG.debug("[ae2ltpp] getChemicalHandler: no handler at {} side {}", pos, side);
            }
            return result;
        } catch (RuntimeException | LinkageError e) {
            LOG.debug("[ae2ltpp] getChemicalHandler: exception at {} side {}: {}", pos, side, e.getMessage());
            return null;
        }
    }

    static long insertChemical(Object handler, Object chemicalStack, boolean simulate) {
        ensureLookup();
        if (insertChemicalMethod == null || actionSimulate == null || actionExecute == null) return 0;
        try {
            Object action = simulate ? actionSimulate : actionExecute;
            Object remainder = insertChemicalMethod.invoke(handler, chemicalStack, action);
            if (remainder == null) return getChemicalAmount(chemicalStack);
            long remainderAmount = getChemicalAmount(remainder);
            return getChemicalAmount(chemicalStack) - remainderAmount;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return 0;
        }
    }

    @Nullable
    static Object extractChemical(Object handler, long amount, boolean simulate) {
        ensureLookup();
        if (extractChemicalMethod == null || actionSimulate == null || actionExecute == null) return null;
        try {
            Object action = simulate ? actionSimulate : actionExecute;
            return extractChemicalMethod.invoke(handler, amount, action);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    @Nullable
    static Object getChemicalInTank(Object handler, int tank) {
        ensureLookup();
        if (getChemicalInTankMethod == null) return null;
        try {
            return getChemicalInTankMethod.invoke(handler, tank);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    static int getChemicalTanks(Object handler) {
        ensureLookup();
        if (getChemicalTanksMethod == null) return 0;
        try {
            return (int) getChemicalTanksMethod.invoke(handler);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return 0;
        }
    }

    static long getChemicalAmount(@Nullable Object chemicalStack) {
        ensureLookup();
        if (chemicalStack == null || chemicalGetAmountMethod == null) return 0;
        try {
            return (long) chemicalGetAmountMethod.invoke(chemicalStack);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return 0;
        }
    }

    static boolean isChemicalEmpty(@Nullable Object chemicalStack) {
        ensureLookup();
        if (chemicalStack == null || chemicalIsEmptyMethod == null) return true;
        try {
            return (boolean) chemicalIsEmptyMethod.invoke(chemicalStack);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return true;
        }
    }

    @Nullable
    static Object copyChemicalWithAmount(@Nullable Object chemicalStack, long amount) {
        ensureLookup();
        if (chemicalStack == null || chemicalCopyWithAmountMethod == null) return null;
        try {
            return chemicalCopyWithAmountMethod.invoke(chemicalStack, amount);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    private static void ensureLookup() {
        if (lookupDone) return;
        synchronized (MekReflection.class) {
            if (lookupDone) return;
            doLookup();
            lookupDone = true;
        }
    }

    private static void doLookup() {
        try {
            tileEntityMekanismClass = ReflectionSupport.findClassCached("mekanism.common.tile.base.TileEntityMekanism")
                    .orElse(null);
            if (tileEntityMekanismClass == null) {
                throw new ClassNotFoundException("mekanism.common.tile.base.TileEntityMekanism");
            }
            isActiveMethod = MekCapabilityReflection.findZeroArgMethod(tileEntityMekanismClass, "getActive", "isActive");
            if (isActiveMethod == null) {
                throw new NoSuchMethodException("mekanism.common.tile.base.TileEntityMekanism#getActive/isActive");
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            LOG.warn("[ae2ltpp] Failed to resolve TileEntityMekanism: {}", e.getMessage());
        }

        try {
            boundingBlockClass = ReflectionSupport.findClassCached("mekanism.common.tile.TileEntityBoundingBlock")
                    .orElse(null);
            getMainPosMethod = boundingBlockClass.getMethod("getMainPos");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            LOG.warn("[ae2ltpp] Failed to resolve TileEntityBoundingBlock: {}", e.getMessage());
        }

        try {
            Class<?> capabilitiesClass = ReflectionSupport.findClassCached("mekanism.common.capabilities.Capabilities")
                    .orElse(null);
            if (capabilitiesClass == null) {
                throw new ClassNotFoundException("mekanism.common.capabilities.Capabilities");
            }
            Field chemicalField = capabilitiesClass.getField("CHEMICAL");
            Object chemicalCapability = chemicalField.get(null);
            chemicalBlockCapability = resolveBlockCapability(chemicalCapability);
            if (chemicalBlockCapability == null) {
                LOG.warn("[ae2ltpp] Resolved Mekanism CHEMICAL field, but it did not expose a Forge Capability: {}",
                        chemicalCapability == null ? "null" : chemicalCapability.getClass().getName());
            } else {
                LOG.debug("[ae2ltpp] Resolved CHEMICAL block capability: {}", chemicalBlockCapability);
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            LOG.warn("[ae2ltpp] Failed to resolve Mekanism CHEMICAL capability: {}", e.getMessage());
        }

        try {
            chemicalHandlerClass = ReflectionSupport.findClassCached("mekanism.api.chemical.IChemicalHandler")
                    .orElse(null);
            chemicalStackClass = ReflectionSupport.findClassCached("mekanism.api.chemical.ChemicalStack")
                    .orElse(null);

            actionClass = ReflectionSupport.findClassCached("mekanism.api.Action").orElse(null);
            Object[] actions = actionClass.getEnumConstants();
            for (Object a : actions) {
                if (a.toString().equals("SIMULATE")) actionSimulate = a;
                else if (a.toString().equals("EXECUTE")) actionExecute = a;
            }

            insertChemicalMethod = chemicalHandlerClass.getMethod("insertChemical", chemicalStackClass, actionClass);
            extractChemicalMethod = chemicalHandlerClass.getMethod("extractChemical", long.class, actionClass);
            getChemicalInTankMethod = chemicalHandlerClass.getMethod("getChemicalInTank", int.class);
            getChemicalTanksMethod = chemicalHandlerClass.getMethod("getChemicalTanks");

            chemicalGetAmountMethod = chemicalStackClass.getMethod("getAmount");
            chemicalIsEmptyMethod = chemicalStackClass.getMethod("isEmpty");
            chemicalCopyWithAmountMethod = chemicalStackClass.getMethod("copyWithAmount", long.class);
            LOG.debug("[ae2ltpp] Resolved all chemical handler methods");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            LOG.warn("[ae2ltpp] Failed to resolve chemical handler API: {}", e.getMessage());
        }
    }

    @Nullable
    private static Object resolveBlockCapability(@Nullable Object capabilityCarrier) {
        return MekCapabilityReflection.resolveBlock(capabilityCarrier, Capability.class);
    }
}
