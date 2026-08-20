package com.moakiee.ae2lt.packaged.logic.multiblock.ec;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.IItemHandlerModifiable;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;

import com.moakiee.ae2lt.packaged.patternprovider.AllowedOutputFilter;
import com.moakiee.ae2lt.packaged.patternprovider.OverloadPatternSemantics;
import com.moakiee.ae2lt.packaged.item.AdapterIds;
import com.moakiee.ae2lt.packaged.logic.multiblock.DispatchPlan;
import com.moakiee.ae2lt.packaged.logic.multiblock.InsertionStrategy;
import com.moakiee.ae2lt.packaged.logic.multiblock.MultiblockAdapter;
import com.moakiee.ae2lt.packaged.logic.multiblock.TargetSlot;
import com.moakiee.ae2lt.packaged.logic.multiblock.binding.BindingMode;
import com.moakiee.ae2lt.packaged.logic.multiblock.binding.BindingResult;

/**
 * Runtime adapter for ExtendedCrafting's Flux Crafter — structurally the
 * same 3x3 layout as the Ender Crafter but progress-driven by FE pulled from
 * nearby {@code flux_alternator} blocks instead of time. Pure reflection.
 *
 * <p>Binding mode is {@link BindingMode#REAL}: completion depends on the
 * recipe's {@code powerRequired} budget being met by alternator extraction.
 * We dispatch ingredients to slots 0-8 and let the BE tick drain the
 * alternator energy storages until the result lands in slot 9.
 *
 * <p>FE balance is *not* checked at dispatch time. Alternators may receive
 * power lazily from external sources after the craft starts; pre-screening
 * would needlessly stall the lane, and the Flux Crafter's own tick is happy
 * to wait for power without dropping items.
 */
public final class ExtendedCraftingFluxCrafterAdapter implements MultiblockAdapter {

    private static final Logger LOG = LoggerFactory.getLogger("ae2ltpp/ec-flux-crafter");

    private static final String MOD_ID = "extendedcrafting";
    private static final ResourceLocation CRAFTER_BLOCK = ecId("flux_crafter");
    private static final ResourceLocation RECIPE_TYPE_ID = ecId("flux_crafter");
    private static final int GRID_SIZE = 9;
    private static final int OUTPUT_SLOT = 9;

    @Override
    public int priority() {
        return 100;
    }

    @Override
    public boolean recognizesMain(ServerLevel level, BlockPos pos, BlockEntity be) {
        return be != null && isEcLoaded() && blockId(be.getBlockState()).equals(CRAFTER_BLOCK);
    }

    @Override
    public ResourceLocation requiredAdapterId(ServerLevel level, BlockPos pos) {
        return AdapterIds.EC_FLUX;
    }

    @Override
    @Nullable
    public BindingResult bind(ServerLevel level, BlockPos mainPos, IPatternDetails pattern) {
        var be = level.getBlockEntity(mainPos);
        if (be == null || !recognizesMain(level, mainPos, be)) return null;
        if (!hasSingleItemOutput(pattern)) return null;

        var recipe = findCandidateRecipe(level, pattern);
        if (recipe == null) return null;
        return new BindingResult(new FluxBindHandle(recipe), BindingMode.REAL);
    }

    @Override
    public boolean canDispatch(ServerLevel level, BlockPos mainPos, Object handle) {
        if (!(handle instanceof FluxBindHandle)) return false;
        var be = level.getBlockEntity(mainPos);
        if (be == null || !recognizesMain(level, mainPos, be)) return false;

        var inv = EcReflection.getInventory(be);
        if (inv == null || inv.getSlots() < GRID_SIZE + 1) return false;

        for (int i = 0; i < GRID_SIZE; i++) {
            if (!inv.getStackInSlot(i).isEmpty()) return false;
        }
        // Neither alternator presence nor FE balance is gated here: both can
        // be set up after dispatch and the BE re-checks them every tick.
        return inv.getStackInSlot(OUTPUT_SLOT).isEmpty();
    }

    @Override
    @Nullable
    public DispatchPlan planWithBinding(ServerLevel level, BlockPos mainPos,
                                        IPatternDetails pattern, KeyCounter[] inputs,
                                        Object handle, IActionSource source) {
        if (!(handle instanceof FluxBindHandle bind)) return null;
        var be = level.getBlockEntity(mainPos);
        if (be == null) return null;

        var inv = EcReflection.getInventory(be);
        if (inv == null || inv.getSlots() < GRID_SIZE + 1) return null;

        var layout = assignGrid(bind.recipe(), inputs);
        if (layout == null) return null;

        var targets = new ArrayList<TargetSlot>(layout.size());
        for (var placement : layout) {
            targets.add(new TargetSlot(
                    level, mainPos, null,
                    List.of(placement.unit().toGenericStack()),
                    InsertionStrategy.CUSTOM,
                    gridInserter(level, mainPos, placement.slot(), placement.unit())));
        }
        return new DispatchPlan(List.copyOf(targets), null);
    }

    @Override
    public List<GenericStack> extractOutputs(ServerLevel level, BlockPos mainPos,
                                             AllowedOutputFilter filter,
                                             IActionSource source) {
        var be = level.getBlockEntity(mainPos);
        if (be == null || !recognizesMain(level, mainPos, be)) return List.of();
        var inv = EcReflection.getInventory(be);
        if (inv == null || inv.getSlots() <= OUTPUT_SLOT) return List.of();

        var stack = inv.getStackInSlot(OUTPUT_SLOT);
        if (stack.isEmpty()) return List.of();

        var key = AEItemKey.of(stack);
        if (!allowsAutoReturn(level, mainPos, filter, key)) return List.of();

        if (!(inv instanceof IItemHandlerModifiable modifiable)) return List.of();
        var copy = stack.copy();
        modifiable.setStackInSlot(OUTPUT_SLOT, ItemStack.EMPTY);
        be.setChanged();
        return List.of(new GenericStack(AEItemKey.of(copy), copy.getCount()));
    }

    // ===== Recipe matching =====

    @Nullable
    private static Object findCandidateRecipe(ServerLevel level, IPatternDetails pattern) {
        for (var holder : recipes(level)) {
            var recipe = holder.value();
            var result = EcReflection.getResultItem(recipe, level);
            if (result == null || result.isEmpty()) continue;
            if (!outputMatches(pattern, result)) continue;
            return recipe;
        }
        return null;
    }

    /**
     * Mirror of the Ender Crafter layout: shaped variants honour the inner
     * {@link net.minecraft.world.item.crafting.ShapedRecipePattern} row/col
     * placement (reflected via {@code getDeclaredField} since the field is
     * private); shapeless packs into the first N slots. A 9-element
     * ingredient list is also treated as 3x3 padded shape as a fallback for
     * cases where pattern reflection misses. The BE's matches() runs via
     * {@code toCraftingInput(3,3,0,9)} so we need the in-grid positions to
     * line up with the recipe's actual shape.
     */
    @Nullable
    private static List<GridPlacement> assignGrid(Object recipe, KeyCounter[] inputs) {
        var ingredients = recipeIngredients(recipe);
        if (ingredients == null || ingredients.isEmpty() || ingredients.size() > GRID_SIZE) return null;

        var units = expandUnits(inputs);
        if (units == null || units.size() != countNonEmpty(ingredients)) return null;

        var placements = new ArrayList<GridPlacement>(ingredients.size());
        boolean[] used = new boolean[units.size()];

        int width = EcReflection.getShapedWidth(recipe);
        int height = EcReflection.getShapedHeight(recipe);
        boolean isShaped = width > 0 && height > 0 && width <= 3 && height <= 3;

        if (!isShaped && ingredients.size() == GRID_SIZE) {
            width = 3;
            height = 3;
            isShaped = true;
        }

        if (isShaped) {
            for (int row = 0; row < height; row++) {
                for (int col = 0; col < width; col++) {
                    int recipeIndex = row * width + col;
                    if (recipeIndex >= ingredients.size()) continue;
                    var ing = ingredients.get(recipeIndex);
                    if (ing.isEmpty()) continue;
                    int gridSlot = row * 3 + col;
                    var unit = pickUnit(units, used, ing);
                    if (unit == null) return null;
                    placements.add(new GridPlacement(gridSlot, unit));
                }
            }
        } else {
            int gridSlot = 0;
            for (var ing : ingredients) {
                if (ing.isEmpty()) continue;
                var unit = pickUnit(units, used, ing);
                if (unit == null) return null;
                placements.add(new GridPlacement(gridSlot++, unit));
            }
        }

        for (boolean u : used) {
            if (!u) return null;
        }
        return placements;
    }

    @Nullable
    private static PlannedUnit pickUnit(List<PlannedUnit> units, boolean[] used, Ingredient ingredient) {
        for (int i = 0; i < units.size(); i++) {
            if (used[i]) continue;
            if (ingredient.test(units.get(i).stack())) {
                used[i] = true;
                return units.get(i);
            }
        }
        return null;
    }

    private static int countNonEmpty(List<Ingredient> ingredients) {
        int n = 0;
        for (var ing : ingredients) {
            if (!ing.isEmpty()) n++;
        }
        return n;
    }

    @Nullable
    private static List<Ingredient> recipeIngredients(Object recipe) {
        if (!(recipe instanceof Recipe<?> r)) return null;
        try {
            return new ArrayList<>(r.getIngredients());
        } catch (RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    // ===== Inserter =====

    private static BiFunction<GenericStack, Actionable, Long> gridInserter(
            ServerLevel level, BlockPos pos, int slot, PlannedUnit unit) {
        return (stack, mode) -> {
            if (stack.amount() != 1 || !unit.key().equals(stack.what())) return 0L;
            var be = level.getBlockEntity(pos);
            if (be == null || !blockId(be.getBlockState()).equals(CRAFTER_BLOCK)) return 0L;
            var inv = EcReflection.getInventory(be);
            if (!(inv instanceof IItemHandlerModifiable modifiable)) return 0L;
            if (modifiable.getSlots() <= slot) return 0L;
            if (!modifiable.getStackInSlot(slot).isEmpty()) return 0L;
            if (mode == Actionable.MODULATE) {
                modifiable.setStackInSlot(slot, unit.stack());
                be.setChanged();
            }
            return 1L;
        };
    }

    // ===== Helpers =====

    @Nullable
    private static List<PlannedUnit> expandUnits(KeyCounter[] inputs) {
        var units = new ArrayList<PlannedUnit>();
        long total = 0;
        for (var counter : inputs) {
            for (var entry : counter) {
                if (!(entry.getKey() instanceof AEItemKey itemKey)) return null;
                long amount = entry.getLongValue();
                if (amount <= 0) continue;
                total += amount;
                if (total > GRID_SIZE) return null;
                for (long i = 0; i < amount; i++) {
                    units.add(new PlannedUnit(itemKey));
                }
            }
        }
        return units;
    }

    private static boolean hasSingleItemOutput(IPatternDetails pattern) {
        var outputs = pattern.getOutputs();
        return outputs.size() == 1 && outputs.getFirst().what() instanceof AEItemKey;
    }

    private static boolean outputMatches(IPatternDetails pattern, ItemStack result) {
        var outputs = pattern.getOutputs();
        if (outputs.size() != 1) return false;
        var expected = outputs.getFirst();
        if (!(expected.what() instanceof AEItemKey expectedKey) || expected.amount() != result.getCount()) {
            return false;
        }
        var actual = AEItemKey.of(result);
        return OverloadPatternSemantics.isIdOnlyOutput(pattern, 0)
                ? expectedKey.dropSecondary().equals(actual.dropSecondary())
                : expectedKey.equals(actual);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static List<RecipeHolder<?>> recipes(ServerLevel level) {
        return BuiltInRegistries.RECIPE_TYPE.getOptional(RECIPE_TYPE_ID)
                .map(type -> (List<RecipeHolder<?>>) (List<?>) level.getRecipeManager()
                        .getAllRecipesFor((RecipeType) type))
                .orElse(List.of());
    }

    private static boolean isEcLoaded() {
        return ModList.get().isLoaded(MOD_ID);
    }

    private static ResourceLocation blockId(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock());
    }

    private static ResourceLocation ecId(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }

    // ===== Records =====

    private record PlannedUnit(AEItemKey key) {
        PlannedUnit {
            Objects.requireNonNull(key, "key");
        }

        ItemStack stack() {
            return key.toStack(1);
        }

        GenericStack toGenericStack() {
            return new GenericStack(key, 1);
        }
    }

    private record GridPlacement(int slot, PlannedUnit unit) {}

    private record FluxBindHandle(Object recipe) {}

    // ===== Reflection =====

    private static final class EcReflection {
        private static final String BASE_INVENTORY_CLASS =
                "com.blakebr0.cucumber.tileentity.BaseInventoryTileEntity";
        private static final String SHAPED_RECIPE_PATTERN =
                "net.minecraft.world.item.crafting.ShapedRecipePattern";

        private static volatile boolean lookupDone;
        private static volatile @Nullable Method getInventoryMethod;
        private static volatile @Nullable Method shapedWidthMethod;
        private static volatile @Nullable Method shapedHeightMethod;
        private static volatile @Nullable Class<?> shapedRecipePatternClass;

        @Nullable
        static IItemHandler getInventory(BlockEntity be) {
            ensureLookup();
            if (getInventoryMethod == null) return null;
            try {
                var value = getInventoryMethod.invoke(be);
                return value instanceof IItemHandler h ? h : null;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                return null;
            }
        }

        @Nullable
        static ItemStack getResultItem(Object recipe, ServerLevel level) {
            if (!(recipe instanceof Recipe<?> r)) return null;
            try {
                return r.getResultItem(level.registryAccess());
            } catch (RuntimeException | LinkageError ignored) {
                return null;
            }
        }

        static int getShapedWidth(Object recipe) {
            ensureLookup();
            return invokePatternDimension(recipe, shapedWidthMethod);
        }

        static int getShapedHeight(Object recipe) {
            ensureLookup();
            return invokePatternDimension(recipe, shapedHeightMethod);
        }

        private static int invokePatternDimension(Object recipe, @Nullable Method m) {
            if (m == null || shapedRecipePatternClass == null) return -1;
            var pattern = findPattern(recipe);
            if (pattern == null) return -1;
            try {
                var value = m.invoke(pattern);
                return value instanceof Integer i ? i : -1;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                return -1;
            }
        }

        /** Same recipe-class walk as EnderCrafter — see that adapter's doc. */
        @Nullable
        private static Object findPattern(Object recipe) {
            var patternClass = shapedRecipePatternClass;
            if (patternClass == null) return null;
            for (Class<?> c = recipe.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (var f : c.getDeclaredFields()) {
                    if (patternClass.isAssignableFrom(f.getType())) {
                        try {
                            f.setAccessible(true);
                            return f.get(recipe);
                        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                            return null;
                        }
                    }
                }
            }
            return null;
        }

        private static void ensureLookup() {
            if (lookupDone) return;
            synchronized (EcReflection.class) {
                if (lookupDone) return;
                try {
                    var baseInventoryClass = Class.forName(BASE_INVENTORY_CLASS);
                    getInventoryMethod = baseInventoryClass.getMethod("getInventory");
                } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                    LOG.warn("FluxCrafter reflection: BaseInventoryTileEntity#getInventory lookup failed: {}",
                            e.toString());
                }
                try {
                    var patternClass = Class.forName(SHAPED_RECIPE_PATTERN);
                    shapedRecipePatternClass = patternClass;
                    shapedWidthMethod = patternClass.getMethod("width");
                    shapedHeightMethod = patternClass.getMethod("height");
                } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                    LOG.warn("FluxCrafter reflection: ShapedRecipePattern dimension methods missing: {}",
                            e.toString());
                }
                lookupDone = true;
            }
        }
    }
}
