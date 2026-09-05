package com.moakiee.ae2lt.packaged.logic.multiblock.mekmm;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

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
    // 1.20.x Mekanism splits chemicals into four per-type Forge capabilities
    // (mekanism.common.capabilities.Capabilities); the unified CHEMICAL
    // capability only exists on 1.21+.
    private static final List<Object> chemicalBlockCapabilities = new ArrayList<>();
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
    private static volatile @Nullable Method chemicalCopyMethod;
    private static volatile @Nullable Method chemicalSetAmountMethod;

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
        List<Object> capabilities = chemicalBlockCapabilities;
        if (capabilities.isEmpty()) {
            LOG.debug("[ae2ltpp] getChemicalHandler: no chemical capabilities resolved");
            return null;
        }
        for (Object capability : capabilities) {
            try {
                var cap = (Capability<T>) capability;
                T result = BlockCapabilities.find(level, pos, cap, side);
                if (result != null) {
                    return result;
                }
            } catch (RuntimeException | LinkageError e) {
                LOG.debug("[ae2ltpp] getChemicalHandler: exception at {} side {}: {}",
                        pos, side, e.getMessage());
            }
        }
        return null;
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
        if (chemicalStack == null) return null;
        if (chemicalCopyWithAmountMethod != null) {
            try {
                return chemicalCopyWithAmountMethod.invoke(chemicalStack, amount);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            }
        }
        // 1.20.x has no copyWithAmount; copy() + setAmount(long) is equivalent.
        if (chemicalCopyMethod == null || chemicalSetAmountMethod == null) return null;
        try {
            Object copy = chemicalCopyMethod.invoke(chemicalStack);
            if (copy == null) return null;
            chemicalSetAmountMethod.invoke(copy, amount);
            return copy;
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
            // 1.20.x exposes one Forge capability per chemical type; resolve all
            // four so handlers of any type are found (fields are missing on other
            // versions, which just shrinks the list).
            for (String fieldName : new String[] {"GAS_HANDLER", "INFUSION_HANDLER", "PIGMENT_HANDLER", "SLURRY_HANDLER"}) {
                try {
                    Field chemicalField = capabilitiesClass.getField(fieldName);
                    Object chemicalCapability = chemicalField.get(null);
                    Object blockCapability = resolveBlockCapability(chemicalCapability);
                    if (blockCapability != null) {
                        chemicalBlockCapabilities.add(blockCapability);
                    } else {
                        LOG.warn("[ae2ltpp] Resolved Mekanism {} field, but it did not expose a Forge Capability: {}",
                                fieldName, chemicalCapability == null ? "null" : chemicalCapability.getClass().getName());
                    }
                } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                    LOG.warn("[ae2ltpp] Failed to resolve Mekanism chemical capability {}: {}", fieldName, e.getMessage());
                }
            }
            if (chemicalBlockCapabilities.isEmpty()) {
                LOG.warn("[ae2ltpp] No Mekanism chemical capability resolved; chemical dispatch disabled");
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            LOG.warn("[ae2ltpp] Failed to resolve Mekanism chemical capabilities: {}", e.getMessage());
        }

        // Each member resolves independently so one missing name (version drift)
        // cannot take the whole chemical API down.
        chemicalHandlerClass = ReflectionSupport.findClassCached("mekanism.api.chemical.IChemicalHandler")
                .orElse(null);
        chemicalStackClass = ReflectionSupport.findClassCached("mekanism.api.chemical.ChemicalStack")
                .orElse(null);

        try {
            actionClass = ReflectionSupport.findClassCached("mekanism.api.Action").orElse(null);
            Object[] actions = actionClass.getEnumConstants();
            for (Object a : actions) {
                if (a.toString().equals("SIMULATE")) actionSimulate = a;
                else if (a.toString().equals("EXECUTE")) actionExecute = a;
            }
        } catch (RuntimeException | LinkageError e) {
            LOG.warn("[ae2ltpp] Failed to resolve Mekanism Action constants: {}", e.getMessage());
        }

        if (chemicalHandlerClass != null && chemicalStackClass != null) {
            insertChemicalMethod = tryMethod(chemicalHandlerClass, "insertChemical", chemicalStackClass, actionClass);
            extractChemicalMethod = tryMethod(chemicalHandlerClass, "extractChemical", long.class, actionClass);
            getChemicalInTankMethod = tryMethod(chemicalHandlerClass, "getChemicalInTank", int.class);
            // 1.20.x names this getTanks(); getChemicalTanks() only exists on
            // 1.21+.
            getChemicalTanksMethod = tryMethod(chemicalHandlerClass, "getTanks");
            if (getChemicalTanksMethod == null) {
                getChemicalTanksMethod = tryMethod(chemicalHandlerClass, "getChemicalTanks");
            }
            if (insertChemicalMethod == null || extractChemicalMethod == null
                    || getChemicalInTankMethod == null || getChemicalTanksMethod == null) {
                LOG.warn("[ae2ltpp] Incomplete IChemicalHandler resolution: insert={}, extract={}, getInTank={}, tankCount={}",
                        insertChemicalMethod != null, extractChemicalMethod != null,
                        getChemicalInTankMethod != null, getChemicalTanksMethod != null);
            }
        } else {
            LOG.warn("[ae2ltpp] Failed to load chemical API classes: handler={}, stack={}",
                    chemicalHandlerClass, chemicalStackClass);
        }

        if (chemicalStackClass != null) {
            chemicalGetAmountMethod = tryMethod(chemicalStackClass, "getAmount");
            chemicalIsEmptyMethod = tryMethod(chemicalStackClass, "isEmpty");
            chemicalCopyWithAmountMethod = tryMethod(chemicalStackClass, "copyWithAmount", long.class);
            chemicalCopyMethod = tryMethod(chemicalStackClass, "copy");
            chemicalSetAmountMethod = tryMethod(chemicalStackClass, "setAmount", long.class);
            if (chemicalGetAmountMethod == null || chemicalIsEmptyMethod == null
                    || (chemicalCopyWithAmountMethod == null
                        && (chemicalCopyMethod == null || chemicalSetAmountMethod == null))) {
                LOG.warn("[ae2ltpp] Incomplete ChemicalStack resolution: amount={}, empty={}, copyWithAmount={}, copy={}, setAmount={}",
                        chemicalGetAmountMethod != null, chemicalIsEmptyMethod != null,
                        chemicalCopyWithAmountMethod != null, chemicalCopyMethod != null,
                        chemicalSetAmountMethod != null);
            }
        }
    }

    @Nullable
    private static Object resolveBlockCapability(@Nullable Object capabilityCarrier) {
        return MekCapabilityReflection.resolveBlock(capabilityCarrier, Capability.class);
    }

    @Nullable
    private static Method tryMethod(@Nullable Class<?> declaring, @Nullable String name, @Nullable Class<?>... parameterTypes) {
        if (declaring == null || name == null || parameterTypes == null) return null;
        for (Class<?> parameterType : parameterTypes) {
            if (parameterType == null) return null;
        }
        try {
            return declaring.getMethod(name, parameterTypes);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return null;
        }
    }
}
