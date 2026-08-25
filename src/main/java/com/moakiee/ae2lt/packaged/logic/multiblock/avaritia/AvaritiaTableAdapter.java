package com.moakiee.ae2lt.packaged.logic.multiblock.avaritia;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.IItemHandlerModifiable;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;

import com.moakiee.ae2lt.packaged.patternprovider.AllowedOutputFilter;
import com.moakiee.ae2lt.packaged.patternprovider.OverloadPatternSemantics;
import com.moakiee.ae2lt.packaged.item.AdapterIds;
import com.moakiee.ae2lt.packaged.logic.multiblock.DispatchPlan;
import com.moakiee.ae2lt.packaged.logic.multiblock.VirtualCraftingAdapter;
import com.moakiee.ae2lt.packaged.logic.multiblock.VirtualCraftingResult;
import com.moakiee.ae2lt.packaged.logic.multiblock.binding.BindingMode;
import com.moakiee.ae2lt.packaged.logic.multiblock.binding.BindingResult;

/**
 * Runtime adapter for Re-Avaritia's tiered crafting tables.
 * Pure reflection: Re-Avaritia is not a compile dependency.
 */
public final class AvaritiaTableAdapter implements VirtualCraftingAdapter {

    private static final String MOD_ID = "avaritia";
    private static final ResourceLocation SCULK_TABLE_BLOCK = avaritiaId("sculk_crafting_table");
    private static final ResourceLocation NETHER_TABLE_BLOCK = avaritiaId("nether_crafting_table");
    private static final ResourceLocation END_TABLE_BLOCK = avaritiaId("end_crafting_table");
    private static final ResourceLocation EXTREME_TABLE_BLOCK = avaritiaId("extreme_crafting_table");
    private static final ResourceLocation RECIPE_TYPE_ID = avaritiaId("crafting_table_recipe");

    @Override
    public int priority() {
        return 100;
    }

    @Override
    public boolean recognizesMain(ServerLevel level, net.minecraft.core.BlockPos pos, @Nullable BlockEntity be) {
        return be != null
                && isAvaritiaLoaded()
                && tableSpec(be.getBlockState()) != null
                && AvaritiaReflection.isSupportedTable(be);
    }

    @Override
    public ResourceLocation requiredAdapterId(ServerLevel level, net.minecraft.core.BlockPos pos) {
        var spec = tableSpec(level.getBlockState(pos));
        if (spec == null) {
            return AdapterIds.AVARITIA_SCULK_TABLE;
        }
        return switch (spec.tier()) {
            case 1 -> AdapterIds.AVARITIA_SCULK_TABLE;
            case 2 -> AdapterIds.AVARITIA_NETHER_TABLE;
            case 3 -> AdapterIds.AVARITIA_END_TABLE;
            case 4 -> AdapterIds.AVARITIA_EXTREME_TABLE;
            default -> AdapterIds.AVARITIA_SCULK_TABLE;
        };
    }

    @Override
    @Nullable
    public BindingResult bind(ServerLevel level, net.minecraft.core.BlockPos mainPos, IPatternDetails pattern) {
        var be = level.getBlockEntity(mainPos);
        if (be == null || !recognizesMain(level, mainPos, be)) {
            return null;
        }
        if (!hasSingleItemOutput(pattern)) {
            return null;
        }
        var spec = tableSpec(be.getBlockState());
        if (spec == null) {
            return null;
        }
        var handle = searchHandle(level, pattern, spec);
        if (handle == null) {
            return null;
        }
        return new BindingResult(handle, BindingMode.VIRTUAL);
    }

    @Override
    public boolean canDispatch(ServerLevel level, net.minecraft.core.BlockPos mainPos, Object handle) {
        return true;
    }

    @Override
    @Nullable
    public DispatchPlan planWithBinding(ServerLevel level, net.minecraft.core.BlockPos mainPos,
                                        IPatternDetails pattern, KeyCounter[] inputs,
                                        Object handle, IActionSource source) {
        return null;
    }

    @Override
    @Nullable
    public VirtualCraftingResult planVirtualWithBinding(ServerLevel level, net.minecraft.core.BlockPos mainPos,
                                                        IPatternDetails pattern, KeyCounter[] inputs,
                                                        Object handle, IActionSource source) {
        if (!(handle instanceof TableBindHandle bind)) {
            return null;
        }
        var spec = bind.spec();

        var be = level.getBlockEntity(mainPos);
        if (be == null || !recognizesMain(level, mainPos, be) || !spec.matches(be.getBlockState())) {
            return null;
        }

        var handler = AvaritiaReflection.getHandler(be);
        if (handler == null || handler.getSlots() < spec.slots() || !gridIsEmpty(handler, spec.slots())) {
            return null;
        }

        var ingredients = ingredients(bind.recipe());
        if (ingredients == null) {
            return null;
        }
        int nonEmpty = countNonEmpty(ingredients);
        if (nonEmpty == 0 || nonEmpty > spec.slots()) {
            return null;
        }

        var available = new LinkedHashMap<AEItemKey, Long>();
        for (var counter : inputs) {
            for (var entry : counter) {
                if (!(entry.getKey() instanceof AEItemKey itemKey)) {
                    return null;
                }
                long amount = entry.getLongValue();
                if (amount <= 0) {
                    continue;
                }
                available.merge(itemKey, amount, Long::sum);
            }
        }
        if (available.isEmpty()) {
            return null;
        }

        int k = bind.craftRatio();
        var perCraftStacks = assignIngredientsStrict(ingredients, available, k);
        if (perCraftStacks == null) {
            return null;
        }

        var planned = layoutGridFromIngredients(bind.recipe(), perCraftStacks, spec);
        if (planned == null) {
            return null;
        }

        var input = AvaritiaReflection.createTierInput(
                spec.gridSize(), stacksForPlan(spec.slots(), planned), spec.tier());
        if (input == null || !recipeMatches(bind.recipe(), input, level)) {
            return null;
        }

        var result = assemble(bind.recipe(), input, level);
        if (result == null || result.isEmpty()) {
            return null;
        }
        long totalCount = (long) k * result.getCount();
        if (totalCount <= 0 || totalCount > Integer.MAX_VALUE) {
            return null;
        }
        if (!outputMatchesBatch(pattern, result, totalCount)) {
            return null;
        }

        var remaining = remainingItems(bind.recipe(), input);
        if (remaining == null) {
            return null;
        }

        var outputs = virtualOutputs(result, remaining, k);
        if (outputs.isEmpty()) {
            return null;
        }
        return new VirtualCraftingResult(outputs);
    }

    @Override
    public List<GenericStack> extractOutputs(ServerLevel level, net.minecraft.core.BlockPos mainPos,
                                             AllowedOutputFilter filter,
                                             IActionSource source) {
        var be = level.getBlockEntity(mainPos);
        if (be == null || !recognizesMain(level, mainPos, be)) {
            return List.of();
        }

        var spec = tableSpec(be.getBlockState());
        var handler = AvaritiaReflection.getHandler(be);
        if (spec == null || !(handler instanceof IItemHandlerModifiable modifiable)
                || handler.getSlots() < spec.slots() || gridIsEmpty(handler, spec.slots())) {
            return List.of();
        }

        var input = AvaritiaReflection.createTierInput(
                spec.gridSize(), currentStacks(handler, spec.slots()), spec.tier());
        if (input == null) {
            return List.of();
        }

        var recipe = findMatchingRecipe(level, input);
        if (recipe == null) {
            return List.of();
        }

        var result = assemble(recipe, input, level);
        if (result == null || result.isEmpty()) {
            return List.of();
        }

        var key = AEItemKey.of(result);
        if (!allowsAutoReturn(level, mainPos, filter, key)) {
            return List.of();
        }

        var remaining = remainingItems(recipe, input);
        if (remaining == null || !canApplyRemaining(handler, spec.gridSize(), input, remaining)) {
            return List.of();
        }

        applyCraftRemainders(modifiable, spec.gridSize(), input, remaining);
        return List.of(new GenericStack(AEItemKey.of(result), result.getCount()));
    }

    private static List<GenericStack> virtualOutputs(ItemStack result, NonNullList<ItemStack> remaining,
                                                     int craftRatio) {
        var outputs = new ArrayList<GenericStack>();
        outputs.add(new GenericStack(AEItemKey.of(result), (long) craftRatio * result.getCount()));
        for (var stack : remaining) {
            if (!stack.isEmpty()) {
                outputs.add(new GenericStack(AEItemKey.of(stack), (long) craftRatio * stack.getCount()));
            }
        }
        return List.copyOf(outputs);
    }

    @Nullable
    private static TableBindHandle searchHandle(ServerLevel level, IPatternDetails pattern, TableSpec spec) {
        var patternOut = pattern.getOutputs()[0];
        if (!(patternOut.what() instanceof AEItemKey patternOutKey) || patternOut.amount() <= 0) {
            return null;
        }
        long patternOutAmount = patternOut.amount();

        var provided = new LinkedHashMap<AEItemKey, Long>();
        for (var input : pattern.getInputs()) {
            var possible = input.getPossibleInputs();
            if (possible.length == 0 || !(possible[0].what() instanceof AEItemKey itemKey)) {
                return null;
            }
            long stackAmount = Math.max(1L, possible[0].amount());
            long multiplier = Math.max(1L, input.getMultiplier());
            long amount;
            try {
                amount = Math.multiplyExact(stackAmount, multiplier);
            } catch (ArithmeticException ignored) {
                return null;
            }
            provided.merge(itemKey, amount, Long::sum);
        }
        if (provided.isEmpty()) {
            return null;
        }

        boolean overload = OverloadPatternSemantics.isIdOnlyOutput(pattern, 0);

        for (var holder : recipes(level)) {
            var recipe = holder;
            if (!tableCanCraftRecipe(spec, recipe)) {
                continue;
            }
            var resultPreview = resultItem(recipe, level);
            if (resultPreview == null || resultPreview.isEmpty()) {
                continue;
            }
            if (resultPreview.getItem() != patternOutKey.getItem()) {
                continue;
            }
            if (!overload && !patternOutKey.equals(AEItemKey.of(resultPreview))) {
                continue;
            }

            int resultCount = resultPreview.getCount();
            if (resultCount <= 0 || patternOutAmount % resultCount != 0) {
                continue;
            }
            long k = patternOutAmount / resultCount;
            if (k <= 0 || k > Integer.MAX_VALUE) {
                continue;
            }

            var ingredients = ingredients(recipe);
            if (ingredients == null) {
                continue;
            }
            int nonEmpty = countNonEmpty(ingredients);
            if (nonEmpty == 0 || nonEmpty > spec.slots()) {
                continue;
            }

            boolean ok = overload
                    ? canAssignIngredientsByItemId(ingredients, provided, k)
                    : canAssignIngredientsStrict(ingredients, provided, k);
            if (!ok) {
                continue;
            }

            return new TableBindHandle(spec, recipe, (int) k);
        }
        return null;
    }

    private static boolean tableCanCraftRecipe(TableSpec spec, Object recipe) {
        int recipeTier = AvaritiaReflection.recipeTier(recipe);
        if (recipeTier <= 0) {
            return true;
        }
        return AvaritiaReflection.recipeHasRequiredTier(recipe)
                ? spec.tier() == recipeTier
                : spec.tier() >= recipeTier;
    }

    private static boolean canAssignIngredientsStrict(NonNullList<Ingredient> ingredients,
                                                      LinkedHashMap<AEItemKey, Long> available,
                                                      long k) {
        return assignIngredientKeys(ingredients, available, k,
                (ingredient, key) -> ingredient.test(key.toStack(1))) != null;
    }

    private static boolean canAssignIngredientsByItemId(NonNullList<Ingredient> ingredients,
                                                        LinkedHashMap<AEItemKey, Long> available,
                                                        long k) {
        return assignIngredientKeys(ingredients, available, k,
                (ingredient, key) -> ingredientAcceptsItemId(ingredient, key.getItem())) != null;
    }

    @Nullable
    private static List<ItemStack> assignIngredientsStrict(NonNullList<Ingredient> ingredients,
                                                           LinkedHashMap<AEItemKey, Long> available,
                                                           long k) {
        var assignedKeys = assignIngredientKeys(ingredients, available, k,
                (ingredient, key) -> ingredient.test(key.toStack(1)));
        if (assignedKeys == null) {
            return null;
        }

        var stacks = new ArrayList<ItemStack>(assignedKeys.size());
        for (var key : assignedKeys) {
            stacks.add(key.toStack(1));
        }
        return List.copyOf(stacks);
    }

    @Nullable
    private static List<AEItemKey> assignIngredientKeys(NonNullList<Ingredient> ingredients,
                                                        LinkedHashMap<AEItemKey, Long> available,
                                                        long k,
                                                        java.util.function.BiPredicate<Ingredient, AEItemKey> matcher) {
        var nonEmptyIngredients = new ArrayList<Ingredient>();
        for (var ingredient : ingredients) {
            if (!ingredient.isEmpty()) {
                nonEmptyIngredients.add(ingredient);
            }
        }
        if (nonEmptyIngredients.isEmpty()) {
            return null;
        }

        var inputs = new ArrayList<AvaritiaTableIngredientAssigner.Input<AEItemKey>>(available.size());
        for (var entry : available.entrySet()) {
            inputs.add(new AvaritiaTableIngredientAssigner.Input<>(entry.getKey(), entry.getValue()));
        }
        return AvaritiaTableIngredientAssigner.assign(nonEmptyIngredients, inputs, k, matcher);
    }

    private static boolean ingredientAcceptsItemId(Ingredient ingredient, Item item) {
        try {
            for (var candidate : ingredient.getItems()) {
                if (candidate.getItem() == item) {
                    return true;
                }
            }
        } catch (RuntimeException | LinkageError ignored) {
        }
        return false;
    }

    private static int countNonEmpty(NonNullList<Ingredient> ingredients) {
        int count = 0;
        for (var ingredient : ingredients) {
            if (!ingredient.isEmpty()) {
                count++;
            }
        }
        return count;
    }

    @Nullable
    private static List<PlannedUnit> layoutGridFromIngredients(Object recipe,
                                                               List<ItemStack> perCraftStacks,
                                                               TableSpec spec) {
        var ingredients = ingredients(recipe);
        if (ingredients == null || ingredients.isEmpty()) {
            return null;
        }

        var width = AvaritiaReflection.recipeWidth(recipe);
        if (width == null) {
            if (perCraftStacks.size() > spec.slots()) {
                return null;
            }
            var planned = new ArrayList<PlannedUnit>(perCraftStacks.size());
            for (int i = 0; i < perCraftStacks.size(); i++) {
                int row = i / spec.gridSize();
                int col = i % spec.gridSize();
                if (row >= spec.gridSize()) {
                    return null;
                }
                planned.add(new PlannedUnit(row * spec.gridSize() + col, perCraftStacks.get(i).copy()));
            }
            return List.copyOf(planned);
        }

        if (width <= 0 || width > spec.gridSize()) {
            return null;
        }

        var recipeCells = new ArrayList<ItemStack>(ingredients.size());
        int nonEmptyIndex = 0;
        for (var ingredient : ingredients) {
            if (ingredient.isEmpty()) {
                recipeCells.add(null);
                continue;
            }
            if (nonEmptyIndex >= perCraftStacks.size()) {
                return null;
            }
            recipeCells.add(perCraftStacks.get(nonEmptyIndex++).copy());
        }
        if (nonEmptyIndex != perCraftStacks.size()) {
            return null;
        }

        var assignments = AvaritiaTableGridPlanner.place(width, spec.gridSize(), recipeCells);
        if (assignments.isEmpty() && !perCraftStacks.isEmpty()) {
            return null;
        }

        var planned = new ArrayList<PlannedUnit>(assignments.size());
        for (var assignment : assignments) {
            planned.add(new PlannedUnit(assignment.slot(), assignment.value().copy()));
        }
        return List.copyOf(planned);
    }

    @Nullable
    private static Object findMatchingRecipe(ServerLevel level, CraftingContainer input) {
        for (var holder : recipes(level)) {
            var recipe = holder;
            if (recipeMatches(recipe, input, level)) {
                return recipe;
            }
        }
        return null;
    }

    private static boolean canApplyRemaining(IItemHandler handler, int gridSize, CraftingContainer input,
                                             NonNullList<ItemStack> remaining) {
        if (remaining.size() < input.getContainerSize()) {
            return false;
        }
        int top = AvaritiaReflection.top(input);
        int left = AvaritiaReflection.left(input);
        for (int y = 0; y < input.getHeight(); y++) {
            for (int x = 0; x < input.getWidth(); x++) {
                int inputIndex = x + y * input.getWidth();
                int slot = x + left + (y + top) * gridSize;
                var current = handler.getStackInSlot(slot);
                if (current.isEmpty()) {
                    continue;
                }

                var remainder = remaining.get(inputIndex);
                if (remainder.isEmpty()) {
                    continue;
                }

                int afterConsume = current.getCount() - 1;
                if (afterConsume <= 0) {
                    continue;
                }
                if (!ItemStack.isSameItemSameTags(current, remainder)) {
                    return false;
                }
                if (afterConsume + remainder.getCount() > current.getMaxStackSize()) {
                    return false;
                }
            }
        }
        return true;
    }

    private static void applyCraftRemainders(IItemHandlerModifiable handler, int gridSize,
                                             CraftingContainer input,
                                             NonNullList<ItemStack> remaining) {
        int top = AvaritiaReflection.top(input);
        int left = AvaritiaReflection.left(input);
        for (int y = 0; y < input.getHeight(); y++) {
            for (int x = 0; x < input.getWidth(); x++) {
                int inputIndex = x + y * input.getWidth();
                int slot = x + left + (y + top) * gridSize;
                var current = handler.getStackInSlot(slot);
                if (!current.isEmpty()) {
                    handler.extractItem(slot, 1, false);
                }

                var remainder = remaining.get(inputIndex);
                if (remainder.isEmpty()) {
                    continue;
                }

                var after = handler.getStackInSlot(slot);
                if (after.isEmpty()) {
                    handler.setStackInSlot(slot, remainder.copy());
                } else if (ItemStack.isSameItemSameTags(after, remainder)) {
                    var combined = after.copy();
                    combined.grow(remainder.getCount());
                    handler.setStackInSlot(slot, combined);
                }
            }
        }
    }

    private static boolean gridIsEmpty(IItemHandler handler, int slots) {
        for (int i = 0; i < slots; i++) {
            if (!handler.getStackInSlot(i).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private static List<ItemStack> stacksForPlan(int slots, List<PlannedUnit> planned) {
        var stacks = NonNullList.withSize(slots, ItemStack.EMPTY);
        for (var unit : planned) {
            stacks.set(unit.slot(), unit.stack().copy());
        }
        return stacks;
    }

    private static List<ItemStack> currentStacks(IItemHandler handler, int slots) {
        var stacks = NonNullList.withSize(slots, ItemStack.EMPTY);
        for (int i = 0; i < slots; i++) {
            stacks.set(i, handler.getStackInSlot(i).copy());
        }
        return stacks;
    }

    private static boolean hasSingleItemOutput(IPatternDetails pattern) {
        var outputs = pattern.getOutputs();
        return outputs.length == 1 && outputs[0].what() instanceof AEItemKey;
    }

    private static boolean outputMatchesBatch(IPatternDetails pattern, ItemStack result,
                                              long expectedTotalCount) {
        var outputs = pattern.getOutputs();
        if (outputs.length != 1) {
            return false;
        }
        var expected = outputs[0];
        if (!(expected.what() instanceof AEItemKey expectedKey)
                || expected.amount() != expectedTotalCount) {
            return false;
        }
        var actual = AEItemKey.of(result);
        return OverloadPatternSemantics.isIdOnlyOutput(pattern, 0)
                ? expectedKey.dropSecondary().equals(actual.dropSecondary())
                : expectedKey.equals(actual);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static boolean recipeMatches(Object recipe, CraftingContainer input, ServerLevel level) {
        try {
            return recipe instanceof Recipe<?> r && ((Recipe) r).matches(input, level);
        } catch (RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    @Nullable
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static ItemStack assemble(Object recipe, CraftingContainer input, ServerLevel level) {
        try {
            return recipe instanceof Recipe<?> r
                    ? ((Recipe) r).assemble(input, level.registryAccess())
                    : null;
        } catch (RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    @Nullable
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static NonNullList<ItemStack> remainingItems(Object recipe, CraftingContainer input) {
        try {
            return recipe instanceof Recipe<?> r
                    ? ((Recipe) r).getRemainingItems(input)
                    : null;
        } catch (RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    @Nullable
    private static ItemStack resultItem(Object recipe, ServerLevel level) {
        if (!(recipe instanceof Recipe<?> r)) {
            return null;
        }
        try {
            return r.getResultItem(level.registryAccess());
        } catch (RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    @Nullable
    private static NonNullList<Ingredient> ingredients(Object recipe) {
        if (!(recipe instanceof Recipe<?> r)) {
            return null;
        }
        try {
            return r.getIngredients();
        } catch (RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static List<Recipe<?>> recipes(ServerLevel level) {
        return BuiltInRegistries.RECIPE_TYPE.getOptional(RECIPE_TYPE_ID)
                .map(type -> (List<Recipe<?>>) (List<?>) level.getRecipeManager()
                        .getAllRecipesFor((RecipeType) type))
                .orElse(List.of());
    }

    @Nullable
    private static TableSpec tableSpec(BlockState state) {
        var id = blockId(state);
        if (id.equals(SCULK_TABLE_BLOCK)) {
            return new TableSpec(SCULK_TABLE_BLOCK, 3, 1);
        }
        if (id.equals(NETHER_TABLE_BLOCK)) {
            return new TableSpec(NETHER_TABLE_BLOCK, 5, 2);
        }
        if (id.equals(END_TABLE_BLOCK)) {
            return new TableSpec(END_TABLE_BLOCK, 7, 3);
        }
        if (id.equals(EXTREME_TABLE_BLOCK)) {
            return new TableSpec(EXTREME_TABLE_BLOCK, 9, 4);
        }
        return null;
    }

    private static boolean isAvaritiaLoaded() {
        return ModList.get().isLoaded(MOD_ID);
    }

    private static ResourceLocation blockId(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock());
    }

    private static ResourceLocation avaritiaId(String path) {
        return new ResourceLocation(MOD_ID, path);
    }

    private record TableSpec(ResourceLocation blockId, int gridSize, int tier) {
        int slots() {
            return gridSize * gridSize;
        }

        boolean matches(BlockState state) {
            return AvaritiaTableAdapter.blockId(state).equals(blockId);
        }
    }

    private record PlannedUnit(int slot, ItemStack stack) {
        PlannedUnit {
            Objects.requireNonNull(stack, "stack");
        }
    }

    private record TableBindHandle(TableSpec spec, Object recipe, int craftRatio) {
    }

    private static final class AvaritiaReflection {
        private static final String TABLE_CLASS =
                "committee.nova.mods.avaritia.common.tile.TierCraftTile";
        private static final String BASE_INVENTORY_CLASS =
                "committee.nova.mods.avaritia.api.common.tile.BaseInventoryTileEntity";
        private static final String TIER_INPUT_CLASS =
                "committee.nova.mods.avaritia.api.common.crafting.TierInput";
        private static final String SHAPED_RECIPE_CLASS =
                "committee.nova.mods.avaritia.common.crafting.recipe.ShapedTableCraftingRecipe";
        private static final String TIER_RECIPE_CLASS =
                "committee.nova.mods.avaritia.api.common.crafting.ITierCraftingRecipe";

        private static volatile boolean lookupDone;
        private static volatile @Nullable Class<?> tableClass;
        private static volatile @Nullable Method getInventoryMethod;
        private static volatile @Nullable Method tierInputOfMethod;
        private static volatile @Nullable Method tierInputTopMethod;
        private static volatile @Nullable Method tierInputLeftMethod;
        private static volatile @Nullable Method shapedRecipeWidthMethod;
        private static volatile @Nullable Method recipeTierMethod;
        private static volatile @Nullable Method recipeHasRequiredTierMethod;

        static boolean isSupportedTable(Object value) {
            ensureLookup();
            return tableClass != null && tableClass.isInstance(value);
        }

        @Nullable
        static IItemHandler getHandler(BlockEntity be) {
            ensureLookup();
            if (getInventoryMethod == null) {
                return null;
            }
            try {
                var value = getInventoryMethod.invoke(be);
                return value instanceof IItemHandler handler ? handler : null;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                return null;
            }
        }

        @Nullable
        static CraftingContainer createTierInput(int size, List<ItemStack> stacks, int tier) {
            ensureLookup();
            if (tierInputOfMethod == null) {
                return null;
            }
            try {
                var value = tierInputOfMethod.invoke(null, size, size, stacks, tier);
                return value instanceof CraftingContainer input ? input : null;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                return null;
            }
        }

        static int top(CraftingContainer input) {
            ensureLookup();
            if (tierInputTopMethod == null) {
                return 0;
            }
            try {
                var value = tierInputTopMethod.invoke(input);
                return value instanceof Integer i ? i : 0;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                return 0;
            }
        }

        static int left(CraftingContainer input) {
            ensureLookup();
            if (tierInputLeftMethod == null) {
                return 0;
            }
            try {
                var value = tierInputLeftMethod.invoke(input);
                return value instanceof Integer i ? i : 0;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                return 0;
            }
        }

        @Nullable
        static Integer recipeWidth(Object recipe) {
            ensureLookup();
            if (shapedRecipeWidthMethod == null) {
                return null;
            }
            try {
                if (!shapedRecipeWidthMethod.getDeclaringClass().isInstance(recipe)) {
                    return null;
                }
                var value = shapedRecipeWidthMethod.invoke(recipe);
                return value instanceof Integer i ? i : null;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                return null;
            }
        }

        static int recipeTier(Object recipe) {
            ensureLookup();
            if (recipeTierMethod == null
                    || !recipeTierMethod.getDeclaringClass().isInstance(recipe)) {
                return 0;
            }
            try {
                var value = recipeTierMethod.invoke(recipe);
                return value instanceof Integer i ? i : 0;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                return 0;
            }
        }

        static boolean recipeHasRequiredTier(Object recipe) {
            ensureLookup();
            if (recipeHasRequiredTierMethod == null
                    || !recipeHasRequiredTierMethod.getDeclaringClass().isInstance(recipe)) {
                return false;
            }
            try {
                var value = recipeHasRequiredTierMethod.invoke(recipe);
                return value instanceof Boolean b && b;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                return false;
            }
        }

        private static void ensureLookup() {
            if (lookupDone) {
                return;
            }
            synchronized (AvaritiaReflection.class) {
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
            tableClass = Class.forName(TABLE_CLASS);

            var baseInventoryClass = Class.forName(BASE_INVENTORY_CLASS);
            getInventoryMethod = baseInventoryClass.getMethod("getInventory");

            var tierInputClass = Class.forName(TIER_INPUT_CLASS);
            tierInputOfMethod = tierInputClass.getMethod("of", int.class, int.class, List.class, int.class);
            tierInputTopMethod = tierInputClass.getMethod("top");
            tierInputLeftMethod = tierInputClass.getMethod("left");

            var shapedRecipeClass = Class.forName(SHAPED_RECIPE_CLASS);
            shapedRecipeWidthMethod = shapedRecipeClass.getMethod("getWidth");

            var tierRecipeClass = Class.forName(TIER_RECIPE_CLASS);
            recipeTierMethod = tierRecipeClass.getMethod("getTier");
            recipeHasRequiredTierMethod = tierRecipeClass.getMethod("hasRequiredTier");
        }
    }
}
