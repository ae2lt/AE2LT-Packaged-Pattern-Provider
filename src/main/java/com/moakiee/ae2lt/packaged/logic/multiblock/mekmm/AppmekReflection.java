package com.moakiee.ae2lt.packaged.logic.multiblock.mekmm;

import java.lang.reflect.Method;

import com.mojang.logging.LogUtils;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import net.minecraftforge.fml.ModList;

import appeng.api.stacks.AEKey;

final class AppmekReflection {

    private static final Logger LOG = LogUtils.getLogger();
    private static final String APPMEK_MOD_ID = "appmek";

    private static volatile boolean lookupDone;
    private static volatile @Nullable Class<?> mekanismKeyClass;
    private static volatile @Nullable Method ofMethod;
    private static volatile @Nullable Method getStackMethod;
    private static volatile @Nullable Method copyWithAmountMethod;
    private static volatile @Nullable Method copyMethod;
    private static volatile @Nullable Method setAmountMethod;

    private AppmekReflection() {}

    static boolean isAppmekLoaded() {
        return ModList.get().isLoaded(APPMEK_MOD_ID);
    }

    static boolean isMekanismKey(AEKey key) {
        ensureLookup();
        return mekanismKeyClass != null && mekanismKeyClass.isInstance(key);
    }

    @Nullable
    static Object toChemicalStack(AEKey key) {
        ensureLookup();
        if (getStackMethod == null || !isMekanismKey(key)) return null;
        try {
            return getStackMethod.invoke(key);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    @Nullable
    static Object toChemicalStackWithAmount(AEKey key, long amount) {
        ensureLookup();
        if (getStackMethod == null || !isMekanismKey(key)) return null;
        try {
            Object stack = getStackMethod.invoke(key);
            if (stack == null) return null;
            if (copyWithAmountMethod != null) {
                return copyWithAmountMethod.invoke(stack, amount);
            }
            // 1.20.x has no copyWithAmount; copy() + setAmount(long) instead.
            if (copyMethod == null || setAmountMethod == null) return null;
            Object copy = copyMethod.invoke(stack);
            if (copy == null) return null;
            setAmountMethod.invoke(copy, amount);
            return copy;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    @Nullable
    static AEKey fromChemicalStack(Object chemicalStack) {
        ensureLookup();
        if (ofMethod == null || chemicalStack == null) return null;
        try {
            return (AEKey) ofMethod.invoke(null, chemicalStack);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    private static void ensureLookup() {
        if (lookupDone) return;
        synchronized (AppmekReflection.class) {
            if (lookupDone) return;
            doLookup();
            lookupDone = true;
        }
    }

    private static void doLookup() {
        try {
            mekanismKeyClass = Class.forName("me.ramidzkh.mekae2.ae2.MekanismKey");
            getStackMethod = mekanismKeyClass.getMethod("getStack");
            LOG.debug("[ae2ltpp] Resolved MekanismKey class and getStack()");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            LOG.warn("[ae2ltpp] Failed to resolve MekanismKey: {}", e.getMessage());
            return;
        }

        try {
            Class<?> chemicalStackClass = Class.forName("mekanism.api.chemical.ChemicalStack");
            ofMethod = mekanismKeyClass.getMethod("of", chemicalStackClass);
            // 1.21+ offers copyWithAmount(long); 1.20.x only has
            // copy() + setAmount(long), so both paths are resolved.
            copyWithAmountMethod = tryMethod(chemicalStackClass, "copyWithAmount", long.class);
            copyMethod = tryMethod(chemicalStackClass, "copy");
            setAmountMethod = tryMethod(chemicalStackClass, "setAmount", long.class);
            if (copyWithAmountMethod == null && (copyMethod == null || setAmountMethod == null)) {
                LOG.warn("[ae2ltpp] No ChemicalStack copy-with-amount member found: copyWithAmount={}, copy={}, setAmount={}",
                        copyWithAmountMethod != null, copyMethod != null, setAmountMethod != null);
            } else {
                LOG.debug("[ae2ltpp] Resolved MekanismKey.of() and chemical copy members");
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            LOG.warn("[ae2ltpp] Failed to resolve chemical conversion methods: {}", e.getMessage());
        }
    }

    @Nullable
    private static Method tryMethod(Class<?> declaring, String name, Class<?>... parameterTypes) {
        try {
            return declaring.getMethod(name, parameterTypes);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return null;
        }
    }
}
