package com.moakiee.ae2lt.packaged.logic.multiblock.ma;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
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
import com.moakiee.ae2lt.packaged.logic.multiblock.DispatchPlan;
import com.moakiee.ae2lt.packaged.logic.multiblock.InsertionStrategy;
import com.moakiee.ae2lt.packaged.logic.multiblock.MultiblockAdapter;
import com.moakiee.ae2lt.packaged.logic.multiblock.TargetSlot;
import com.moakiee.ae2lt.packaged.logic.multiblock.binding.BindingMode;
import com.moakiee.ae2lt.packaged.logic.multiblock.binding.BindingResult;
import com.moakiee.ae2lt.packaged.patternprovider.OverloadPatternSemantics;

/**
 * Runtime adapter for Mystical Agriculture's Awakening Altar.
 * Pure reflection: MA is not a compile dependency.
 *
 * Layout: 1 altar + 8 sub-positions. Each sub-position must hold either an
 * AwakeningPedestal (item slot, capacity 1) or an EssenceVessel (essence slot,
 * capacity 40). The recipe requires exactly 4 pedestals + 4 vessels; their
 * physical placement among the 8 positions does not matter.
 */
public final class AwakeningAltarAdapter implements MultiblockAdapter {

    private static final String MOD_ID = "mysticalagriculture";
    private static final ResourceLocation ALTAR_BLOCK = maId("awakening_altar");
    private static final ResourceLocation PEDESTAL_BLOCK = maId("awakening_pedestal");
    private static final ResourceLocation VESSEL_BLOCK = maId("essence_vessel");
    private static final ResourceLocation RECIPE_TYPE_ID = maId("awakening");
    private static final int VESSEL_CAPACITY = 40;
    private static final int MAX_INPUT_UNITS = 256;

    private static final BlockPos[] SUB_OFFSETS = {
            new BlockPos(-3, 0, 0),
            new BlockPos(2, 0, 2),
            new BlockPos(3, 0, 0),
            new BlockPos(-2, 0, -2),
            new BlockPos(0, 0, -3),
            new BlockPos(2, 0, -2),
            new BlockPos(0, 0, 3),
            new BlockPos(-2, 0, 2),
    };

    @Override
    public int priority() {
        return 100;
    }

    @Override
    public boolean recognizesMain(ServerLevel level, BlockPos pos, BlockEntity be) {
        return be != null
                && isMaLoaded()
                && blockId(be.getBlockState()).equals(ALTAR_BLOCK)
                && AwakeningReflection.isAwakeningAltar(be);
    }

    @Override
    public ResourceLocation requiredAdapterId(ServerLevel level, BlockPos pos) {
        return com.moakiee.ae2lt.packaged.item.AdapterIds.MA_AWAKENING;
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
        var recipe = findCandidateRecipe(level, pattern);
        if (recipe == null) {
            return null;
        }
        return new BindingResult(new AwakeningBindHandle(recipe), BindingMode.REAL);
    }

    @Override
    public boolean canDispatch(ServerLevel level, BlockPos mainPos, Object handle) {
        if (!(handle instanceof AwakeningBindHandle)) {
            return false;
        }
        var be = level.getBlockEntity(mainPos);
        if (be == null || !recognizesMain(level, mainPos, be)) {
            return false;
        }
        if (AwakeningReflection.getProgress(be) > 0) {
            return false;
        }
        var altarInv = AwakeningReflection.getHandler(be);
        if (altarInv == null || altarInv.getSlots() < 2) {
            return false;
        }
        if (!altarInv.getStackInSlot(0).isEmpty() || !altarInv.getStackInSlot(1).isEmpty()) {
            return false;
        }
        var slots = scanSubSlots(level, mainPos);
        return slots != null && slots.pedestals().size() == 4 && slots.vessels().size() == 4;
    }

    @Override
    @Nullable
    public DispatchPlan planWithBinding(ServerLevel level, BlockPos mainPos,
                                        IPatternDetails pattern, KeyCounter[] inputs,
                                        Object handle, IActionSource source) {
        if (!(handle instanceof AwakeningBindHandle bind)) {
            return null;
        }
        var slots = scanSubSlots(level, mainPos);
        if (slots == null || slots.pedestals().size() != 4 || slots.vessels().size() != 4) {
            return null;
        }

        var keyTotals = aggregateInputs(inputs);
        if (keyTotals == null || keyTotals.isEmpty()) {
            return null;
        }

        var match = assignInputsToRecipe(bind.recipe(), keyTotals);
        if (match == null) {
            return null;
        }

        var targets = new ArrayList<TargetSlot>(9);
        targets.add(new TargetSlot(
                level, mainPos, null,
                List.of(new GenericStack(match.altarKey(), 1)),
                InsertionStrategy.CUSTOM,
                altarInserter(level, mainPos, match.altarKey())));

        for (int i = 0; i < match.essenceAssignments().size(); i++) {
            var assignment = match.essenceAssignments().get(i);
            var vesselPos = slots.vessels().get(i);
            targets.add(new TargetSlot(
                    level, vesselPos, null,
                    List.of(new GenericStack(assignment.key(), assignment.count())),
                    InsertionStrategy.CUSTOM,
                    vesselInserter(level, vesselPos, assignment.key(), assignment.count())));
        }

        for (int i = 0; i < match.pedestalKeys().size(); i++) {
            var key = match.pedestalKeys().get(i);
            var pedestalPos = slots.pedestals().get(i);
            targets.add(new TargetSlot(
                    level, pedestalPos, null,
                    List.of(new GenericStack(key, 1)),
                    InsertionStrategy.CUSTOM,
                    pedestalInserter(level, pedestalPos, key)));
        }

        return new DispatchPlan(List.copyOf(targets),
                () -> AwakeningReflection.activate(level, mainPos));
    }

    @Override
    public List<GenericStack> extractOutputs(ServerLevel level, BlockPos mainPos,
                                             AllowedOutputFilter filter,
                                             IActionSource source) {
        var be = level.getBlockEntity(mainPos);
        if (be == null || !recognizesMain(level, mainPos, be)) {
            return List.of();
        }
        if (AwakeningReflection.getProgress(be) > 0) {
            return List.of();
        }

        var handler = AwakeningReflection.getHandler(be);
        if (handler == null || handler.getSlots() < 2) {
            return List.of();
        }

        var stack = handler.getStackInSlot(1);
        if (stack.isEmpty()) {
            return List.of();
        }

        var key = AEItemKey.of(stack);
        if (!allowsAutoReturn(level, mainPos, filter, key)) {
            return List.of();
        }

        var extracted = handler.extractItem(1, stack.getCount(), false);
        if (extracted.isEmpty()) {
            return List.of();
        }
        return List.of(new GenericStack(AEItemKey.of(extracted), extracted.getCount()));
    }

    /**
     * Bind-time recipe search: filters by pattern.outputs and structural
     * requirements only. Caching this result lets {@code planWithBinding} skip
     * the recipe-table sweep on every subsequent push.
     */
    @Nullable
    private static Object findCandidateRecipe(ServerLevel level, IPatternDetails pattern) {
        for (var holder : recipes(level)) {
            var recipe = holder.value();
            var result = resultItem(recipe, level);
            if (result == null || result.isEmpty() || !outputMatches(pattern, result)) {
                continue;
            }
            var altarIngredient = AwakeningReflection.getAltarIngredient(recipe);
            var essenceStacks = AwakeningReflection.getEssences(recipe);
            var ingredients = AwakeningReflection.getIngredients(recipe);
            if (altarIngredient == null || essenceStacks == null || ingredients == null) {
                continue;
            }
            if (essenceStacks.size() != 4 || ingredients.size() != 8) {
                continue;
            }
            if (pedestalIngredients(ingredients) == null) {
                continue;
            }
            return recipe;
        }
        return null;
    }

    /**
     * Per-push input assignment against the recipe locked in by {@link #bind}.
     * Tries each input key as the altar item, then greedily consumes the
     * remaining keyTotals to satisfy essence vessels + pedestal ingredients.
     */
    @Nullable
    private static RecipeMatch assignInputsToRecipe(Object recipe, Map<AEItemKey, Long> keyTotals) {
        var altarIngredient = AwakeningReflection.getAltarIngredient(recipe);
        var essenceStacks = AwakeningReflection.getEssences(recipe);
        var ingredients = AwakeningReflection.getIngredients(recipe);
        if (altarIngredient == null || essenceStacks == null || ingredients == null) {
            return null;
        }
        var pedestalIngredients = pedestalIngredients(ingredients);
        if (pedestalIngredients == null) {
            return null;
        }

        for (var altarEntry : keyTotals.entrySet()) {
            var altarKey = altarEntry.getKey();
            if (altarEntry.getValue() < 1 || !altarIngredient.test(altarKey.toStack())) {
                continue;
            }

            var working = new HashMap<>(keyTotals);
            if (!consume(working, altarKey, 1)) {
                continue;
            }

            var essenceAssignments = consumeEssences(working, essenceStacks);
            if (essenceAssignments == null) {
                continue;
            }

            var pedestalKeys = consumePedestals(working, pedestalIngredients);
            if (pedestalKeys == null) {
                continue;
            }
            if (anyRemaining(working)) {
                continue;
            }

            return new RecipeMatch(altarKey, essenceAssignments, pedestalKeys);
        }
        return null;
    }

    @Nullable
    private static List<EssenceAssignment> consumeEssences(Map<AEItemKey, Long> working,
                                                           List<ItemStack> essenceStacks) {
        var assignments = new ArrayList<EssenceAssignment>(essenceStacks.size());
        for (var essence : essenceStacks) {
            if (essence.isEmpty()) {
                return null;
            }
            var key = AEItemKey.of(essence);
            int count = essence.getCount();
            if (count <= 0 || count > VESSEL_CAPACITY || !consume(working, key, count)) {
                return null;
            }
            assignments.add(new EssenceAssignment(key, count));
        }
        return List.copyOf(assignments);
    }

    @Nullable
    private static List<AEItemKey> consumePedestals(Map<AEItemKey, Long> working,
                                                    List<Ingredient> ingredients) {
        var result = new ArrayList<AEItemKey>(ingredients.size());
        for (var ingredient : ingredients) {
            AEItemKey matched = null;
            for (var entry : working.entrySet()) {
                if (entry.getValue() < 1) {
                    continue;
                }
                if (ingredient.test(entry.getKey().toStack())) {
                    matched = entry.getKey();
                    break;
                }
            }
            if (matched == null || !consume(working, matched, 1)) {
                return null;
            }
            result.add(matched);
        }
        return List.copyOf(result);
    }

    @Nullable
    private static List<Ingredient> pedestalIngredients(List<Ingredient> alternating) {
        // AwakeningRecipe.inputs is built as [essence, ingredient, essence, ingredient, ...]
        // The odd indexes (1,3,5,7) are the pedestal ingredients (originally recipe.inputs in JSON).
        var result = new ArrayList<Ingredient>(4);
        for (int i = 1; i < alternating.size(); i += 2) {
            var ing = alternating.get(i);
            if (ing == null) {
                return null;
            }
            result.add(ing);
        }
        return result.size() == 4 ? result : null;
    }

    private static boolean consume(Map<AEItemKey, Long> working, AEItemKey key, int count) {
        var current = working.getOrDefault(key, 0L);
        if (current < count) {
            return false;
        }
        long next = current - count;
        if (next == 0) {
            working.remove(key);
        } else {
            working.put(key, next);
        }
        return true;
    }

    private static boolean anyRemaining(Map<AEItemKey, Long> working) {
        for (var v : working.values()) {
            if (v > 0) {
                return true;
            }
        }
        return false;
    }

    @Nullable
    private static SubSlots scanSubSlots(ServerLevel level, BlockPos mainPos) {
        var pedestals = new ArrayList<BlockPos>(4);
        var vessels = new ArrayList<BlockPos>(4);
        for (var offset : SUB_OFFSETS) {
            var pos = mainPos.offset(offset.getX(), offset.getY(), offset.getZ());
            if (!level.isLoaded(pos)) {
                return null;
            }
            var be = level.getBlockEntity(pos);
            if (be == null) {
                return null;
            }
            var id = blockId(be.getBlockState());
            if (id.equals(PEDESTAL_BLOCK) && AwakeningReflection.isAwakeningPedestal(be)) {
                var handler = AwakeningReflection.getHandler(be);
                if (handler == null || handler.getSlots() < 1 || !handler.getStackInSlot(0).isEmpty()) {
                    return null;
                }
                if (pedestals.size() >= 4) {
                    return null;
                }
                pedestals.add(pos);
            } else if (id.equals(VESSEL_BLOCK) && AwakeningReflection.isEssenceVessel(be)) {
                var handler = AwakeningReflection.getHandler(be);
                if (handler == null || handler.getSlots() < 1 || !handler.getStackInSlot(0).isEmpty()) {
                    return null;
                }
                if (vessels.size() >= 4) {
                    return null;
                }
                vessels.add(pos);
            } else {
                return null;
            }
        }
        return new SubSlots(List.copyOf(pedestals), List.copyOf(vessels));
    }

    private static BiFunction<GenericStack, Actionable, Long> altarInserter(
            ServerLevel level, BlockPos pos, AEItemKey key) {
        return (stack, mode) -> {
            if (stack.amount() != 1 || !key.equals(stack.what())) {
                return 0L;
            }
            var be = level.getBlockEntity(pos);
            if (be == null || !blockId(be.getBlockState()).equals(ALTAR_BLOCK)
                    || !AwakeningReflection.isAwakeningAltar(be)) {
                return 0L;
            }
            var handler = AwakeningReflection.getHandler(be);
            if (!(handler instanceof IItemHandlerModifiable modifiable)
                    || modifiable.getSlots() < 2
                    || !modifiable.getStackInSlot(0).isEmpty()
                    || !modifiable.getStackInSlot(1).isEmpty()) {
                return 0L;
            }
            if (mode == Actionable.MODULATE) {
                modifiable.setStackInSlot(0, key.toStack(1));
            }
            return 1L;
        };
    }

    private static BiFunction<GenericStack, Actionable, Long> pedestalInserter(
            ServerLevel level, BlockPos pos, AEItemKey key) {
        return (stack, mode) -> {
            if (stack.amount() != 1 || !key.equals(stack.what())) {
                return 0L;
            }
            var be = level.getBlockEntity(pos);
            if (be == null || !blockId(be.getBlockState()).equals(PEDESTAL_BLOCK)
                    || !AwakeningReflection.isAwakeningPedestal(be)) {
                return 0L;
            }
            var handler = AwakeningReflection.getHandler(be);
            if (!(handler instanceof IItemHandlerModifiable modifiable)
                    || modifiable.getSlots() < 1
                    || !modifiable.getStackInSlot(0).isEmpty()) {
                return 0L;
            }
            if (mode == Actionable.MODULATE) {
                modifiable.setStackInSlot(0, key.toStack(1));
            }
            return 1L;
        };
    }

    private static BiFunction<GenericStack, Actionable, Long> vesselInserter(
            ServerLevel level, BlockPos pos, AEItemKey key, int count) {
        return (stack, mode) -> {
            if (stack.amount() != count || !key.equals(stack.what())) {
                return 0L;
            }
            if (count <= 0 || count > VESSEL_CAPACITY) {
                return 0L;
            }
            var be = level.getBlockEntity(pos);
            if (be == null || !blockId(be.getBlockState()).equals(VESSEL_BLOCK)
                    || !AwakeningReflection.isEssenceVessel(be)) {
                return 0L;
            }
            var handler = AwakeningReflection.getHandler(be);
            if (!(handler instanceof IItemHandlerModifiable modifiable)
                    || modifiable.getSlots() < 1
                    || !modifiable.getStackInSlot(0).isEmpty()) {
                return 0L;
            }
            if (mode == Actionable.MODULATE) {
                modifiable.setStackInSlot(0, key.toStack(count));
            }
            return (long) count;
        };
    }

    @Nullable
    private static Map<AEItemKey, Long> aggregateInputs(KeyCounter[] inputs) {
        var totals = new HashMap<AEItemKey, Long>();
        long grandTotal = 0;
        for (var counter : inputs) {
            for (var entry : counter) {
                if (!(entry.getKey() instanceof AEItemKey itemKey)) {
                    return null;
                }
                long amount = entry.getLongValue();
                if (amount <= 0) {
                    continue;
                }
                grandTotal += amount;
                if (grandTotal > MAX_INPUT_UNITS) {
                    return null;
                }
                totals.merge(itemKey, amount, Long::sum);
            }
        }
        return totals;
    }

    private static boolean hasSingleItemOutput(IPatternDetails pattern) {
        var outputs = pattern.getOutputs();
        return outputs.size() == 1 && outputs.getFirst().what() instanceof AEItemKey;
    }

    private static boolean outputMatches(IPatternDetails pattern, ItemStack result) {
        var outputs = pattern.getOutputs();
        if (outputs.size() != 1) {
            return false;
        }
        var expected = outputs.getFirst();
        if (!(expected.what() instanceof AEItemKey expectedKey) || expected.amount() != result.getCount()) {
            return false;
        }
        var actual = AEItemKey.of(result);
        return OverloadPatternSemantics.isIdOnlyOutput(pattern, 0)
                ? expectedKey.dropSecondary().equals(actual.dropSecondary())
                : expectedKey.equals(actual);
    }

    @Nullable
    private static ItemStack resultItem(Object recipe, ServerLevel level) {
        if (!(recipe instanceof net.minecraft.world.item.crafting.Recipe<?> r)) {
            return null;
        }
        try {
            return r.getResultItem(level.registryAccess());
        } catch (RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static List<RecipeHolder<?>> recipes(ServerLevel level) {
        return BuiltInRegistries.RECIPE_TYPE.getOptional(RECIPE_TYPE_ID)
                .map(type -> (List<RecipeHolder<?>>) (List<?>) level.getRecipeManager()
                        .getAllRecipesFor((RecipeType) type))
                .orElse(List.of());
    }

    private static boolean isMaLoaded() {
        return ModList.get().isLoaded(MOD_ID);
    }

    private static ResourceLocation blockId(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock());
    }

    private static ResourceLocation maId(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }

    private record EssenceAssignment(AEItemKey key, int count) {
        EssenceAssignment {
            Objects.requireNonNull(key, "key");
        }
    }

    private record RecipeMatch(AEItemKey altarKey,
                               List<EssenceAssignment> essenceAssignments,
                               List<AEItemKey> pedestalKeys) {
    }

    /** Opaque binding handle returned from {@link #bind}. */
    private record AwakeningBindHandle(Object recipe) {
    }

    private record SubSlots(List<BlockPos> pedestals, List<BlockPos> vessels) {
    }

    private static final class AwakeningReflection {
        private static final String ALTAR_CLASS =
                "com.blakebr0.mysticalagriculture.tileentity.AwakeningAltarTileEntity";
        private static final String PEDESTAL_CLASS =
                "com.blakebr0.mysticalagriculture.tileentity.AwakeningPedestalTileEntity";
        private static final String VESSEL_CLASS =
                "com.blakebr0.mysticalagriculture.tileentity.EssenceVesselTileEntity";
        private static final String AWAKENING_RECIPE_API_CLASS =
                "com.blakebr0.mysticalagriculture.api.crafting.IAwakeningRecipe";
        private static final String ACTIVATABLE_CLASS =
                "com.blakebr0.mysticalagriculture.util.IActivatable";
        private static final String BASE_INVENTORY_CLASS =
                "com.blakebr0.cucumber.tileentity.BaseInventoryTileEntity";

        private static volatile boolean lookupDone;
        private static volatile @Nullable Class<?> altarClass;
        private static volatile @Nullable Class<?> pedestalClass;
        private static volatile @Nullable Class<?> vesselClass;
        private static volatile @Nullable Class<?> recipeApiClass;
        private static volatile @Nullable Class<?> activatableClass;
        private static volatile @Nullable Method getInventoryMethod;
        private static volatile @Nullable Method activateMethod;
        private static volatile @Nullable Method getAltarIngredientMethod;
        private static volatile @Nullable Method getEssencesMethod;
        private static volatile @Nullable Method getIngredientsMethod;
        private static volatile @Nullable Field progressField;

        static boolean isAwakeningAltar(Object o) {
            ensureLookup();
            return altarClass != null && altarClass.isInstance(o);
        }

        static boolean isAwakeningPedestal(Object o) {
            ensureLookup();
            return pedestalClass != null && pedestalClass.isInstance(o);
        }

        static boolean isEssenceVessel(Object o) {
            ensureLookup();
            return vesselClass != null && vesselClass.isInstance(o);
        }

        @Nullable
        static IItemHandler getHandler(BlockEntity be) {
            ensureLookup();
            if (getInventoryMethod == null) {
                return null;
            }
            try {
                var value = getInventoryMethod.invoke(be);
                return value instanceof IItemHandler h ? h : null;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                return null;
            }
        }

        static int getProgress(BlockEntity be) {
            ensureLookup();
            if (progressField == null) {
                return 0;
            }
            try {
                return progressField.getInt(be);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                return 0;
            }
        }

        static void activate(ServerLevel level, BlockPos pos) {
            ensureLookup();
            if (activateMethod == null) {
                return;
            }
            var be = level.getBlockEntity(pos);
            if (be == null || activatableClass == null || !activatableClass.isInstance(be)) {
                return;
            }
            try {
                activateMethod.invoke(be);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            }
        }

        @Nullable
        static Ingredient getAltarIngredient(Object recipe) {
            ensureLookup();
            if (getAltarIngredientMethod == null || recipeApiClass == null
                    || !recipeApiClass.isInstance(recipe)) {
                return null;
            }
            try {
                var value = getAltarIngredientMethod.invoke(recipe);
                return value instanceof Ingredient ing ? ing : null;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                return null;
            }
        }

        @Nullable
        @SuppressWarnings("unchecked")
        static List<ItemStack> getEssences(Object recipe) {
            ensureLookup();
            if (getEssencesMethod == null || recipeApiClass == null
                    || !recipeApiClass.isInstance(recipe)) {
                return null;
            }
            try {
                var value = getEssencesMethod.invoke(recipe);
                if (!(value instanceof List<?> list)) {
                    return null;
                }
                var result = new ArrayList<ItemStack>(list.size());
                for (var item : list) {
                    if (!(item instanceof ItemStack stack)) {
                        return null;
                    }
                    result.add(stack);
                }
                return result;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                return null;
            }
        }

        @Nullable
        @SuppressWarnings("unchecked")
        static List<Ingredient> getIngredients(Object recipe) {
            ensureLookup();
            if (getIngredientsMethod == null || !(recipe instanceof net.minecraft.world.item.crafting.Recipe<?>)) {
                return null;
            }
            try {
                var value = getIngredientsMethod.invoke(recipe);
                if (!(value instanceof List<?> list)) {
                    return null;
                }
                var result = new ArrayList<Ingredient>(list.size());
                for (var item : list) {
                    if (!(item instanceof Ingredient ingredient)) {
                        return null;
                    }
                    result.add(ingredient);
                }
                return result;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                return null;
            }
        }

        private static void ensureLookup() {
            if (lookupDone) {
                return;
            }
            synchronized (AwakeningReflection.class) {
                if (lookupDone) {
                    return;
                }
                try {
                    doLookup();
                } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                } finally {
                    lookupDone = true;
                }
            }
        }

        private static void doLookup() throws ReflectiveOperationException {
            altarClass = Class.forName(ALTAR_CLASS);
            pedestalClass = Class.forName(PEDESTAL_CLASS);
            vesselClass = Class.forName(VESSEL_CLASS);
            recipeApiClass = Class.forName(AWAKENING_RECIPE_API_CLASS);
            activatableClass = Class.forName(ACTIVATABLE_CLASS);

            var baseInventoryClass = Class.forName(BASE_INVENTORY_CLASS);
            getInventoryMethod = baseInventoryClass.getMethod("getInventory");

            activateMethod = activatableClass.getMethod("activate");
            getAltarIngredientMethod = recipeApiClass.getMethod("getAltarIngredient");
            getEssencesMethod = recipeApiClass.getMethod("getEssences");
            getIngredientsMethod = net.minecraft.world.item.crafting.Recipe.class.getMethod("getIngredients");

            progressField = altarClass.getDeclaredField("progress");
            progressField.setAccessible(true);
        }
    }
}
