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
 * Runtime adapter for ExtendedCrafting's Combination Crafter — a central
 * {@code crafting_core} surrounded by up to 48 {@code pedestal} blocks
 * within a 7×7 horizontal AOE (same Y level as the core). Pure reflection.
 *
 * <p>The Core stores a catalyst in slot 0 and a {@link
 * net.neoforged.neoforge.energy.EnergyStorage}-backed FE buffer. Pedestals
 * each hold one ingredient. Once enough power has accumulated, the BE
 * consumes the catalyst + pedestal stacks and writes the result back into
 * Core slot 0.
 *
 * <p>Binding mode is {@link BindingMode#REAL} — there's no equivalent
 * virtual evaluation since the recipe demands physical pedestals + tick
 * progress. We deliberately do not gate dispatch on FE balance; external
 * sources can keep refilling the Core while the BE waits.
 */
public final class ExtendedCraftingCombinationAdapter implements MultiblockAdapter {

    private static final Logger LOG = LoggerFactory.getLogger("ae2ltpp/ec-combination");

    private static final String MOD_ID = "extendedcrafting";
    private static final ResourceLocation CORE_BLOCK = ecId("crafting_core");
    private static final ResourceLocation PEDESTAL_BLOCK = ecId("pedestal");
    private static final ResourceLocation RECIPE_TYPE_ID = ecId("combination");
    private static final int CORE_SLOT = 0;
    private static final int PEDESTAL_SLOT = 0;
    private static final int AOE_RADIUS = 3;
    private static final int MAX_INGREDIENTS = 48;

    @Override
    public int priority() {
        return 100;
    }

    @Override
    public boolean recognizesMain(ServerLevel level, BlockPos pos, BlockEntity be) {
        return be != null && isEcLoaded() && blockId(be.getBlockState()).equals(CORE_BLOCK);
    }

    @Override
    public ResourceLocation requiredAdapterId(ServerLevel level, BlockPos pos) {
        return AdapterIds.EC_COMBINATION;
    }

    @Override
    @Nullable
    public BindingResult bind(ServerLevel level, BlockPos mainPos, IPatternDetails pattern) {
        var be = level.getBlockEntity(mainPos);
        if (be == null || !recognizesMain(level, mainPos, be)) return null;
        if (!hasSingleItemOutput(pattern)) return null;

        var recipe = findCandidateRecipe(level, pattern);
        if (recipe == null) return null;
        return new BindingResult(new CombinationBindHandle(recipe), BindingMode.REAL);
    }

    @Override
    public boolean canDispatch(ServerLevel level, BlockPos mainPos, Object handle) {
        if (!(handle instanceof CombinationBindHandle bind)) return false;
        var be = level.getBlockEntity(mainPos);
        if (be == null || !recognizesMain(level, mainPos, be)) return false;

        var inv = EcReflection.getInventory(be);
        if (inv == null || inv.getSlots() <= CORE_SLOT) return false;
        if (!inv.getStackInSlot(CORE_SLOT).isEmpty()) return false;
        if (EcReflection.getProgress(be) > 0) return false;

        var ingredients = EcReflection.getRecipeIngredients(bind.recipe());
        if (ingredients == null) return false;
        int needed = ingredients.size();
        if (needed <= 0 || needed > MAX_INGREDIENTS) return false;

        return countEmptyPedestals(level, mainPos) >= needed;
    }

    @Override
    @Nullable
    public DispatchPlan planWithBinding(ServerLevel level, BlockPos mainPos,
                                        IPatternDetails pattern, KeyCounter[] inputs,
                                        Object handle, IActionSource source) {
        if (!(handle instanceof CombinationBindHandle bind)) return null;
        var be = level.getBlockEntity(mainPos);
        if (be == null) return null;

        var catalyst = EcReflection.getCatalyst(bind.recipe());
        var ingredients = EcReflection.getRecipeIngredients(bind.recipe());
        if (catalyst == null || ingredients == null) return null;

        var match = matchInputs(catalyst, ingredients, inputs);
        if (match == null) return null;

        var emptyPedestals = findEmptyPedestals(level, mainPos);
        if (emptyPedestals.size() < ingredients.size()) return null;

        var targets = new ArrayList<TargetSlot>(ingredients.size() + 1);
        targets.add(new TargetSlot(
                level, mainPos, null,
                List.of(match.catalyst().toGenericStack()),
                InsertionStrategy.CUSTOM,
                coreInserter(level, mainPos, match.catalyst())));

        for (int i = 0; i < match.ingredients().size(); i++) {
            var unit = match.ingredients().get(i);
            var pedestalPos = emptyPedestals.get(i);
            targets.add(new TargetSlot(
                    level, pedestalPos, null,
                    List.of(unit.toGenericStack()),
                    InsertionStrategy.CUSTOM,
                    pedestalInserter(level, pedestalPos, unit)));
        }

        return new DispatchPlan(List.copyOf(targets), null);
    }

    @Override
    public List<GenericStack> extractOutputs(ServerLevel level, BlockPos mainPos,
                                             AllowedOutputFilter filter,
                                             IActionSource source) {
        var be = level.getBlockEntity(mainPos);
        if (be == null || !recognizesMain(level, mainPos, be)) return List.of();
        // While the craft is in progress the Core slot still holds the
        // catalyst; only drain once the BE has finished and reset progress.
        if (EcReflection.getProgress(be) > 0) return List.of();

        var inv = EcReflection.getInventory(be);
        if (inv == null || inv.getSlots() <= CORE_SLOT) return List.of();

        var stack = inv.getStackInSlot(CORE_SLOT);
        if (stack.isEmpty()) return List.of();

        var key = AEItemKey.of(stack);
        if (!allowsAutoReturn(level, mainPos, filter, key)) return List.of();

        if (!(inv instanceof IItemHandlerModifiable modifiable)) return List.of();
        var copy = stack.copy();
        modifiable.setStackInSlot(CORE_SLOT, ItemStack.EMPTY);
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
     * Greedy shapeless match: catalyst gets a single unit from any input
     * that satisfies {@code recipe.getInput()}; ingredients each pick the
     * first unused unit that satisfies them. Match order on the ingredient
     * side is the recipe's declared order, which is also what the BE checks
     * via {@code RecipeMatcher.findMatches} at completion time.
     */
    @Nullable
    private static InputMatch matchInputs(Ingredient catalyst, List<Ingredient> ingredients,
                                          KeyCounter[] inputs) {
        var units = expandUnits(inputs, 1 + ingredients.size());
        if (units == null || units.size() != 1 + ingredients.size()) return null;

        boolean[] used = new boolean[units.size()];
        PlannedUnit catalystUnit = null;
        for (int i = 0; i < units.size(); i++) {
            if (catalyst.test(units.get(i).stack())) {
                catalystUnit = units.get(i);
                used[i] = true;
                break;
            }
        }
        if (catalystUnit == null) return null;

        var pedestalUnits = new ArrayList<PlannedUnit>(ingredients.size());
        for (var ing : ingredients) {
            PlannedUnit picked = null;
            for (int i = 0; i < units.size(); i++) {
                if (used[i]) continue;
                if (ing.test(units.get(i).stack())) {
                    used[i] = true;
                    picked = units.get(i);
                    break;
                }
            }
            if (picked == null) return null;
            pedestalUnits.add(picked);
        }
        return new InputMatch(catalystUnit, pedestalUnits);
    }

    // ===== Inserters =====

    private static BiFunction<GenericStack, Actionable, Long> coreInserter(
            ServerLevel level, BlockPos pos, PlannedUnit unit) {
        return (stack, mode) -> {
            if (stack.amount() != 1 || !unit.key().equals(stack.what())) return 0L;
            var be = level.getBlockEntity(pos);
            if (be == null || !blockId(be.getBlockState()).equals(CORE_BLOCK)) return 0L;
            if (EcReflection.getProgress(be) > 0) return 0L;
            var inv = EcReflection.getInventory(be);
            if (!(inv instanceof IItemHandlerModifiable modifiable)) return 0L;
            if (modifiable.getSlots() <= CORE_SLOT) return 0L;
            if (!modifiable.getStackInSlot(CORE_SLOT).isEmpty()) return 0L;
            if (mode == Actionable.MODULATE) {
                modifiable.setStackInSlot(CORE_SLOT, unit.stack());
                be.setChanged();
            }
            return 1L;
        };
    }

    private static BiFunction<GenericStack, Actionable, Long> pedestalInserter(
            ServerLevel level, BlockPos pos, PlannedUnit unit) {
        return (stack, mode) -> {
            if (stack.amount() != 1 || !unit.key().equals(stack.what())) return 0L;
            var be = level.getBlockEntity(pos);
            if (be == null || !blockId(be.getBlockState()).equals(PEDESTAL_BLOCK)) return 0L;
            var inv = EcReflection.getInventory(be);
            if (!(inv instanceof IItemHandlerModifiable modifiable)) return 0L;
            if (modifiable.getSlots() <= PEDESTAL_SLOT) return 0L;
            if (!modifiable.getStackInSlot(PEDESTAL_SLOT).isEmpty()) return 0L;
            if (mode == Actionable.MODULATE) {
                modifiable.setStackInSlot(PEDESTAL_SLOT, unit.stack());
                be.setChanged();
            }
            return 1L;
        };
    }

    // ===== World scanning =====

    /**
     * Walks the {@code [-3,0,-3]..[+3,0,+3]} AOE in X→Z order (same iteration
     * as {@code BlockPos.betweenClosedStream}) collecting empty pedestals.
     * The Core's own ingredient scan uses identical order, so the i-th
     * assigned ingredient maps cleanly to the i-th BE-side pedestal at
     * completion.
     */
    private static List<BlockPos> findEmptyPedestals(ServerLevel level, BlockPos mainPos) {
        var result = new ArrayList<BlockPos>();
        var min = mainPos.offset(-AOE_RADIUS, 0, -AOE_RADIUS);
        var max = mainPos.offset(AOE_RADIUS, 0, AOE_RADIUS);
        var cursor = new BlockPos.MutableBlockPos();
        for (int x = min.getX(); x <= max.getX(); x++) {
            for (int z = min.getZ(); z <= max.getZ(); z++) {
                cursor.set(x, mainPos.getY(), z);
                if (cursor.equals(mainPos)) continue;
                if (!level.isLoaded(cursor)) continue;
                if (!blockId(level.getBlockState(cursor)).equals(PEDESTAL_BLOCK)) continue;
                var be = level.getBlockEntity(cursor);
                if (be == null) continue;
                var inv = EcReflection.getInventory(be);
                if (inv == null || inv.getSlots() <= PEDESTAL_SLOT) continue;
                if (!inv.getStackInSlot(PEDESTAL_SLOT).isEmpty()) continue;
                result.add(cursor.immutable());
            }
        }
        return result;
    }

    private static int countEmptyPedestals(ServerLevel level, BlockPos mainPos) {
        return findEmptyPedestals(level, mainPos).size();
    }

    // ===== Helpers =====

    @Nullable
    private static List<PlannedUnit> expandUnits(KeyCounter[] inputs, int cap) {
        var units = new ArrayList<PlannedUnit>();
        long total = 0;
        for (var counter : inputs) {
            for (var entry : counter) {
                if (!(entry.getKey() instanceof AEItemKey itemKey)) return null;
                long amount = entry.getLongValue();
                if (amount <= 0) continue;
                total += amount;
                if (total > cap) return null;
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

    private record InputMatch(PlannedUnit catalyst, List<PlannedUnit> ingredients) {}

    private record CombinationBindHandle(Object recipe) {}

    // ===== Reflection =====

    private static final class EcReflection {
        private static final String BASE_INVENTORY_CLASS =
                "com.blakebr0.cucumber.tileentity.BaseInventoryTileEntity";
        private static final String CORE_TILE_CLASS =
                "com.blakebr0.extendedcrafting.tileentity.CraftingCoreTileEntity";
        private static final String COMBINATION_RECIPE_CLASS =
                "com.blakebr0.extendedcrafting.crafting.recipe.CombinationRecipe";

        private static volatile boolean lookupDone;
        private static volatile @Nullable Method getInventoryMethod;
        private static volatile @Nullable Method getProgressMethod;
        private static volatile @Nullable Method getInputMethod;

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

        static int getProgress(BlockEntity be) {
            ensureLookup();
            if (getProgressMethod == null) return -1;
            try {
                var value = getProgressMethod.invoke(be);
                return value instanceof Integer i ? i : -1;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                return -1;
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

        @Nullable
        static Ingredient getCatalyst(Object recipe) {
            ensureLookup();
            if (getInputMethod == null) return null;
            try {
                var value = getInputMethod.invoke(recipe);
                return value instanceof Ingredient i ? i : null;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                return null;
            }
        }

        @Nullable
        static List<Ingredient> getRecipeIngredients(Object recipe) {
            if (!(recipe instanceof Recipe<?> r)) return null;
            try {
                return new ArrayList<>(r.getIngredients());
            } catch (RuntimeException | LinkageError ignored) {
                return null;
            }
        }

        private static void ensureLookup() {
            if (lookupDone) return;
            synchronized (EcReflection.class) {
                if (lookupDone) return;
                try {
                    var baseInventoryClass = Class.forName(BASE_INVENTORY_CLASS);
                    getInventoryMethod = baseInventoryClass.getMethod("getInventory");
                } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                    LOG.warn("Combination reflection: BaseInventoryTileEntity#getInventory lookup failed: {}",
                            e.toString());
                }
                try {
                    var coreClass = Class.forName(CORE_TILE_CLASS);
                    getProgressMethod = coreClass.getMethod("getProgress");
                } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                    LOG.warn("Combination reflection: CraftingCoreTileEntity#getProgress lookup failed: {}",
                            e.toString());
                }
                try {
                    var recipeClass = Class.forName(COMBINATION_RECIPE_CLASS);
                    getInputMethod = recipeClass.getMethod("getInput");
                } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                    LOG.warn("Combination reflection: CombinationRecipe#getInput lookup failed: {}",
                            e.toString());
                }
                lookupDone = true;
            }
        }
    }
}
