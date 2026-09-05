package com.moakiee.ae2lt.packaged.logic.multiblock.aa;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Objects;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.fml.ModList;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;

import com.moakiee.ae2lt.packaged.patternprovider.AllowedOutputFilter;
import com.moakiee.ae2lt.packaged.patternprovider.OverloadPatternSemantics;
import com.moakiee.ae2lt.packaged.logic.multiblock.AdapterRecipeTypes;
import com.moakiee.ae2lt.packaged.logic.multiblock.DispatchPlan;
import com.moakiee.ae2lt.packaged.logic.multiblock.AdapterBlocks;
import com.moakiee.ae2lt.packaged.logic.multiblock.VirtualCraftingAdapter;
import com.moakiee.ae2lt.packaged.logic.multiblock.VirtualCraftingResult;
import com.moakiee.ae2lt.packaged.logic.multiblock.binding.BindingMode;
import com.moakiee.ae2lt.packaged.logic.multiblock.binding.BindingResult;

/**
 * Virtual adapter for Actually Additions' Atomic Reconstructor laser
 * conversions.
 *
 * <p>The Atomic Reconstructor is "instant" in spirit — once the beam fires
 * (every redstone pulse or once per tick when continuously powered) a single
 * item passes through the laser AABB and converts in the same tick. The mod's
 * baseline {@code MethodHandler.invokeReconstructor} contains no animation
 * delay; the ~visual delay players see is the item-entity drop / settle, not
 * the conversion itself.
 *
 * <p>So we equivalent-virtualize it: compute the output, consume the input
 * from AE, and surface the result through the virtual batch — but still
 * <b>extract the machine's internal RF/CF</b> via the AA API (the canonical
 * {@code IEnergyTile.extractEnergy(int)}), keeping the cost side-effect
 * faithful so the player still has to feed the reconstructor power. If the
 * reconstructor is starved, the batch returns null and the next push reattempts.
 *
 * <p>The default conversion-lens check is preserved as well: only the stock
 * conversion lens triggers vanilla recipes, so other lenses (death, miner,
 * color, …) are silently ignored — those have side effects that aren't safely
 * virtualizable.
 */
public final class ActuallyAdditionsAtomicReconstructorAdapter implements VirtualCraftingAdapter {

    private static final String MOD_ID = "actuallyadditions";
    private static final ResourceLocation ATOMIC_RECONSTRUCTOR_BLOCK = aaId("atomic_reconstructor");
    private static final ResourceLocation LASER_RECIPE_TYPE = aaId("laser");
    /**
     * Per-craft baseline RF charged by the reconstructor regardless of recipe
     * (matches AA's headroom over {@code recipe.getEnergy()} in 1.21 builds).
     */
    private static final int BASE_ENERGY_USE = 1000;
    private static final int MAX_INPUT_AMOUNT = 64;

    @Override
    public int priority() {
        return 90;
    }

    @Override
    public boolean recognizesMain(ServerLevel level, BlockPos pos, BlockEntity be) {
        return be != null
                && isActuallyAdditionsLoaded()
                && blockId(be.getBlockState()).equals(ATOMIC_RECONSTRUCTOR_BLOCK)
                && AaReconstructorReflection.isAtomicReconstructor(be);
    }

    @Override
    public ResourceLocation requiredAdapterId(ServerLevel level, BlockPos pos) {
        return com.moakiee.ae2lt.packaged.item.AdapterIds.AA_RECONSTRUCTOR;
    }

    @Override
    public ResourceLocation flushSoundId() {
        return null;
    }

    @Override
    @Nullable
    public BindingResult bind(ServerLevel level, BlockPos mainPos, IPatternDetails pattern) {
        var be = level.getBlockEntity(mainPos);
        if (be == null || !recognizesMain(level, mainPos, be)) {
            return null;
        }
        if (!hasSingleItemOutput(pattern)) {
            return null;
        }
        var found = findCandidateRecipe(level, pattern);
        if (found == null) {
            return null;
        }
        return new BindingResult(found, BindingMode.VIRTUAL);
    }

    @Override
    public boolean canDispatch(ServerLevel level, BlockPos mainPos, Object handle) {
        // Virtual-only path: live charge / lens checks happen inside
        // planVirtualWithBinding, so the gate is unconditional here.
        return true;
    }

    @Override
    @Nullable
    public DispatchPlan planWithBinding(ServerLevel level, BlockPos mainPos,
                                        IPatternDetails pattern, KeyCounter[] inputs,
                                        Object handle, IActionSource source) {
        // Virtual-only.
        return null;
    }

    @Override
    @Nullable
    public VirtualCraftingResult planVirtualWithBinding(ServerLevel level, BlockPos mainPos,
                                                        IPatternDetails pattern, KeyCounter[] inputs,
                                                        Object handle, IActionSource source) {
        if (!(handle instanceof ReconstructorBindHandle bind)) {
            return null;
        }

        var be = level.getBlockEntity(mainPos);
        if (be == null || !recognizesMain(level, mainPos, be)) {
            return null;
        }
        // Lens gating: only the stock conversion lens runs vanilla recipes
        // virtualizably; specialized lenses (miner, death, ...) have side
        // effects we can't faithfully simulate without world interaction.
        if (!AaReconstructorReflection.hasDefaultConversionLens(be)) {
            return null;
        }

        var input = singleInput(inputs);
        if (input == null) {
            return null;
        }

        var ingredient = AaReconstructorReflection.getInput(bind.recipe());
        if (ingredient == null || !ingredient.test(input.singleStack())) {
            return null;
        }
        var result = resultItem(bind.recipe(), level);
        if (result.isEmpty()) {
            return null;
        }

        // Parallel batch accounting: one push covers the whole batch encoded into the
        // pattern, so we derive the expected total output from input ratio
        // and reject the recipe only when the pattern's declared output
        // doesn't line up.
        long perCraftInput = perCraftInputAmount(pattern);
        if (perCraftInput <= 0 || input.amount() % perCraftInput != 0) {
            return null;
        }
        long craftRatio = input.amount() / perCraftInput;
        long totalCount;
        try {
            totalCount = Math.multiplyExact(craftRatio, (long) result.getCount());
        } catch (ArithmeticException ignored) {
            return null;
        }
        if (totalCount <= 0 || totalCount > Integer.MAX_VALUE) {
            return null;
        }
        if (!outputKeyMatchesBatch(pattern, result, totalCount)) {
            return null;
        }

        // Charge accounting:
        //   per-craft cost = BASE_ENERGY_USE + recipe.getEnergy()
        //   total           = per-craft * craftRatio   (NOT * input.amount,
        //   because each craft transforms a single recipe-input quantity)
        long perCraftCost = (long) BASE_ENERGY_USE + bind.recipeEnergy();
        long totalCost;
        try {
            totalCost = Math.multiplyExact(perCraftCost, craftRatio);
        } catch (ArithmeticException ignored) {
            return null;
        }
        if (totalCost > Integer.MAX_VALUE) {
            return null;
        }
        int storedCharge = AaReconstructorReflection.getEnergy(be);
        if (storedCharge < totalCost) {
            return null;
        }

        // Modulate phase — side effect is intentional and matches what the
        // reconstructor would have drawn for craftRatio physical crafts.
        if (!AaReconstructorReflection.extractEnergy(be, (int) totalCost)) {
            return null;
        }

        return new VirtualCraftingResult(List.of(
                new GenericStack(AEItemKey.of(result), totalCount)));
    }

    @Override
    public void onVirtualBatchFlush(ServerLevel level, BlockPos mainPos, Object handle, IActionSource source) {
        var be = level.getBlockEntity(mainPos);
        if (be == null || !recognizesMain(level, mainPos, be)) {
            return;
        }
        AaReconstructorReflection.invokeReconstructor(be);
    }

    @Override
    public List<GenericStack> extractOutputs(ServerLevel level, BlockPos mainPos,
                                             AllowedOutputFilter filter,
                                             IActionSource source) {
        // Virtual path produces no in-world items.
        return List.of();
    }

    /**
     * Bind-time recipe search: pick the recipe whose result <i>item</i>
     * matches the pattern's primary output, ignoring amount. Batch
     * verification is deferred to plan time where the input ratio is known.
     */
    @Nullable
    private static ReconstructorBindHandle findCandidateRecipe(ServerLevel level, IPatternDetails pattern) {
        for (var recipe : recipes(level)) {
            int energy = AaReconstructorReflection.getRecipeEnergy(recipe);
            if (energy <= 0) {
                continue;
            }
            var result = resultItem(recipe, level);
            if (result.isEmpty()) {
                continue;
            }
            if (!outputKeyMatchesByItem(pattern, result)) {
                continue;
            }
            return new ReconstructorBindHandle(recipe, energy);
        }
        return null;
    }

    private static boolean outputKeyMatchesByItem(IPatternDetails pattern, ItemStack result) {
        var outputs = pattern.getOutputs();
        if (outputs.length != 1) {
            return false;
        }
        var expected = outputs[0];
        if (!(expected.what() instanceof AEItemKey expectedKey)) {
            return false;
        }
        var actual = AEItemKey.of(result);
        return OverloadPatternSemantics.isIdOnlyOutput(pattern, 0)
                ? expectedKey.dropSecondary().equals(actual.dropSecondary())
                : expectedKey.equals(actual);
    }

    private static boolean outputKeyMatchesBatch(IPatternDetails pattern, ItemStack result,
                                                  long expectedTotalCount) {
        var outputs = pattern.getOutputs();
        if (outputs.length != 1) {
            return false;
        }
        var expected = outputs[0];
        if (!(expected.what() instanceof AEItemKey expectedKey)) {
            return false;
        }
        if (expected.amount() != expectedTotalCount) {
            return false;
        }
        var actual = AEItemKey.of(result);
        return OverloadPatternSemantics.isIdOnlyOutput(pattern, 0)
                ? expectedKey.dropSecondary().equals(actual.dropSecondary())
                : expectedKey.equals(actual);
    }

    private static long perCraftInputAmount(IPatternDetails pattern) {
        var inputs = pattern.getInputs();
        if (inputs.length > 0) {
            return Math.max(1, inputs[0].getMultiplier());
        }
        return 1L;
    }

    private static boolean hasSingleItemOutput(IPatternDetails pattern) {
        var outputs = pattern.getOutputs();
        return outputs.length == 1 && outputs[0].what() instanceof AEItemKey;
    }

    @Nullable
    private static PlannedInput singleInput(KeyCounter[] inputs) {
        AEItemKey key = null;
        long amount = 0;

        for (var counter : inputs) {
            for (var entry : counter) {
                if (!(entry.getKey() instanceof AEItemKey itemKey)) {
                    return null;
                }
                long entryAmount = entry.getLongValue();
                if (entryAmount <= 0) {
                    continue;
                }
                if (key == null) {
                    key = itemKey;
                } else if (!key.equals(itemKey)) {
                    return null;
                }
                amount += entryAmount;
                if (amount > MAX_INPUT_AMOUNT) {
                    return null;
                }
            }
        }

        return key != null && amount > 0 ? new PlannedInput(key, amount) : null;
    }

    private static ItemStack resultItem(Object recipe, ServerLevel level) {
        if (!(recipe instanceof Recipe<?> typedRecipe)) {
            return ItemStack.EMPTY;
        }
        try {
            return typedRecipe.getResultItem(level.registryAccess()).copy();
        } catch (RuntimeException | LinkageError ignored) {
            return ItemStack.EMPTY;
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static List<Recipe<?>> recipes(ServerLevel level) {
        var type = AdapterRecipeTypes.find(LASER_RECIPE_TYPE);
        if (type == null) {
            return List.of();
        }
        return (List<Recipe<?>>) (List<?>) level.getRecipeManager()
                .getAllRecipesFor((RecipeType) type);
    }

    private static boolean isActuallyAdditionsLoaded() {
        return ModList.get().isLoaded(MOD_ID);
    }

    private static ResourceLocation blockId(BlockState state) { return AdapterBlocks.idOf(state); }

    private static ResourceLocation aaId(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }

    private record PlannedInput(AEItemKey key, long amount) {
        PlannedInput {
            Objects.requireNonNull(key, "key");
        }

        ItemStack singleStack() {
            return key.toStack(1);
        }
    }

    /** Opaque binding handle returned from {@link #bind}. */
    private record ReconstructorBindHandle(Object recipe, int recipeEnergy) {
    }

    private static final class AaReconstructorReflection {
        private static final org.slf4j.Logger LOG =
                org.slf4j.LoggerFactory.getLogger("ae2ltpp/aa-reconstructor");

        private static final String RECONSTRUCTOR_CLASS =
                "de.ellpeck.actuallyadditions.mod.tile.TileEntityAtomicReconstructor";
        private static final String LENS_CONVERSION_CLASS =
                "de.ellpeck.actuallyadditions.api.lens.LensConversion";
        private static final String LASER_RECIPE_CLASS =
                "de.ellpeck.actuallyadditions.mod.crafting.LaserRecipe";
        private static final String API_CLASS =
                "de.ellpeck.actuallyadditions.api.ActuallyAdditionsAPI";
        private static final String METHOD_HANDLER_CLASS =
                "de.ellpeck.actuallyadditions.api.internal.IMethodHandler";
        private static final String ENERGY_TILE_INTERFACE =
                "de.ellpeck.actuallyadditions.api.internal.IEnergyTile";
        private static final String ATOMIC_RECONSTRUCTOR_INTERFACE =
                "de.ellpeck.actuallyadditions.api.internal.IAtomicReconstructor";

        private static volatile boolean lookupDone;
        private static volatile @Nullable Class<?> reconstructorClass;
        private static volatile @Nullable Class<?> laserRecipeClass;
        private static volatile @Nullable Field methodHandlerField;
        private static volatile @Nullable Method getLensMethod;
        private static volatile @Nullable Method invokeReconstructorMethod;
        private static volatile @Nullable Method getEnergyMethod;
        private static volatile @Nullable Method extractEnergyMethod;
        private static volatile @Nullable Method getInputMethod;
        private static volatile @Nullable Method getRecipeEnergyMethod;

        static boolean isAtomicReconstructor(Object o) {
            ensureLookup();
            return reconstructorClass != null && reconstructorClass.isInstance(o);
        }

        static boolean hasDefaultConversionLens(BlockEntity be) {
            ensureLookup();
            if (getLensMethod == null || !isAtomicReconstructor(be)) {
                return false;
            }
            try {
                var lens = getLensMethod.invoke(be);
                if (lens == null) {
                    return false;
                }
                // Class-name string compare (instead of Class.forName-based
                // isInstance) so we don't depend on the LensConversion class
                // being loadable from our classloader. AA's
                // {@code TileEntityAtomicReconstructor.getLens()} returns
                // either the static {@code ActuallyAdditionsAPI.lensDefaultConversion}
                // singleton (empty slot — typed LensConversion) or a custom
                // {@code Lens} subclass when a specialized lens item is installed.
                // Only LensConversion is virtualizable; everything else
                // (LensMining / LensDeath / LensColor / …) has side effects we
                // can't faithfully replay without world interaction.
                return lens.getClass().getName().equals(LENS_CONVERSION_CLASS);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                return false;
            }
        }

        static boolean invokeReconstructor(BlockEntity be) {
            ensureLookup();
            if (methodHandlerField == null || invokeReconstructorMethod == null || !isAtomicReconstructor(be)) {
                return false;
            }
            try {
                var handler = methodHandlerField.get(null);
                if (handler == null) {
                    return false;
                }
                var value = invokeReconstructorMethod.invoke(handler, be);
                return value instanceof Boolean b && b;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                return false;
            }
        }

        static int getEnergy(BlockEntity be) {
            ensureLookup();
            if (getEnergyMethod == null || !isAtomicReconstructor(be)) {
                return 0;
            }
            try {
                var value = getEnergyMethod.invoke(be);
                return value instanceof Number number ? number.intValue() : 0;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                return 0;
            }
        }

        /**
         * Drains {@code amount} from the reconstructor's internal storage. AA
         * routes this through {@code TileEntityAtomicReconstructor.extractEnergy(int)},
         * which delegates to the underlying {@code storage.extractEnergyInternal}.
         *
         * @return false when the lookup or call fails — caller treats this as
         *         "not enough charge / wrong wiring" and aborts.
         */
        static boolean extractEnergy(BlockEntity be, int amount) {
            ensureLookup();
            if (extractEnergyMethod == null || !isAtomicReconstructor(be)) {
                return false;
            }
            if (amount <= 0) {
                return true;
            }
            try {
                extractEnergyMethod.invoke(be, amount);
                return true;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                return false;
            }
        }

        @Nullable
        static Ingredient getInput(Object recipe) {
            ensureLookup();
            if (getInputMethod == null || !isLaserRecipe(recipe)) {
                return null;
            }
            try {
                var value = getInputMethod.invoke(recipe);
                return value instanceof Ingredient ingredient ? ingredient : null;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                return null;
            }
        }

        static int getRecipeEnergy(Object recipe) {
            ensureLookup();
            if (getRecipeEnergyMethod == null || !isLaserRecipe(recipe)) {
                return 0;
            }
            try {
                var value = getRecipeEnergyMethod.invoke(recipe);
                return value instanceof Number number ? number.intValue() : 0;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                return 0;
            }
        }

        private static boolean isLaserRecipe(Object recipe) {
            return laserRecipeClass != null && laserRecipeClass.isInstance(recipe);
        }

        private static void ensureLookup() {
            if (lookupDone) {
                return;
            }
            synchronized (AaReconstructorReflection.class) {
                if (lookupDone) {
                    return;
                }
                doLookup();
                lookupDone = true;
            }
        }

        /**
         * Best-effort reflection bootstrap. Each step is independently
         * try/catch'd so a single broken signature (e.g. AA renaming a
         * method in a future release) only disables the affected feature,
         * not the whole adapter. Failures are logged at WARN so an admin
         * can spot mismatches without having to reconfigure log levels.
         */
        private static void doLookup() {
            reconstructorClass = tryClass(RECONSTRUCTOR_CLASS);
            laserRecipeClass = tryClass(LASER_RECIPE_CLASS);
            var apiClass = tryClass(API_CLASS);
            var methodHandlerClass = tryClass(METHOD_HANDLER_CLASS);
            var atomicReconstructorInterface = tryClass(ATOMIC_RECONSTRUCTOR_INTERFACE);
            var energyTileInterface = tryClass(ENERGY_TILE_INTERFACE);

            if (apiClass != null) {
                methodHandlerField = tryField(apiClass, "methodHandler");
            }
            if (methodHandlerClass != null && atomicReconstructorInterface != null) {
                invokeReconstructorMethod = tryMethod(methodHandlerClass, "invokeReconstructor",
                        atomicReconstructorInterface);
            }
            if (atomicReconstructorInterface != null) {
                getLensMethod = tryMethod(atomicReconstructorInterface, "getLens");
            }
            if (energyTileInterface != null) {
                getEnergyMethod = tryMethod(energyTileInterface, "getEnergy");
                extractEnergyMethod = tryMethod(energyTileInterface, "extractEnergy", int.class);
            }
            if (laserRecipeClass != null) {
                getInputMethod = tryMethod(laserRecipeClass, "getInput");
                getRecipeEnergyMethod = tryMethod(laserRecipeClass, "getEnergy");
            }

            LOG.info("AA reflection ready: reconstructor={} laser={} methodHandler={} invoke={} getLens={} getEnergy={} extractEnergy={} getInput={} recipeEnergy={}",
                    reconstructorClass != null,
                    laserRecipeClass != null,
                    methodHandlerField != null,
                    invokeReconstructorMethod != null,
                    getLensMethod != null,
                    getEnergyMethod != null,
                    extractEnergyMethod != null,
                    getInputMethod != null,
                    getRecipeEnergyMethod != null);
        }

        @Nullable
        private static Class<?> tryClass(String name) {
            try {
                return Class.forName(name);
            } catch (ClassNotFoundException | LinkageError e) {
                LOG.warn("AA class lookup failed: {} ({})", name, e.getMessage());
                return null;
            }
        }

        @Nullable
        private static Field tryField(Class<?> declaring, String name) {
            try {
                return declaring.getField(name);
            } catch (NoSuchFieldException | LinkageError e) {
                LOG.warn("AA field lookup failed: {}#{} ({})", declaring.getName(), name, e.getMessage());
                return null;
            }
        }

        @Nullable
        private static Method tryMethod(Class<?> declaring, String name, Class<?>... params) {
            try {
                return declaring.getMethod(name, params);
            } catch (NoSuchMethodException | LinkageError e) {
                LOG.warn("AA method lookup failed: {}#{} ({})", declaring.getName(), name, e.getMessage());
                return null;
            }
        }
    }
}
