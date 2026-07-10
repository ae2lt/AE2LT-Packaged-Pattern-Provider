package com.moakiee.ae2lt.packaged.logic.multiblock.ec;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.IItemHandlerModifiable;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;

import com.moakiee.ae2lt.logic.AllowedOutputFilter;
import com.moakiee.thunderbolt.ae2.overload.model.MatchMode;
import com.moakiee.thunderbolt.ae2.overload.pattern.OverloadedProviderOnlyPatternDetails;
import com.moakiee.ae2lt.packaged.item.AdapterIds;
import com.moakiee.ae2lt.packaged.logic.multiblock.DispatchPlan;
import com.moakiee.ae2lt.packaged.logic.multiblock.VirtualCraftingAdapter;
import com.moakiee.ae2lt.packaged.logic.multiblock.VirtualCraftingResult;
import com.moakiee.ae2lt.packaged.logic.multiblock.binding.BindingMode;
import com.moakiee.ae2lt.packaged.logic.multiblock.binding.BindingResult;

/**
 * Runtime adapter for ExtendedCrafting's ordinary crafting tables.
 * Pure reflection: ExtendedCrafting is not a compile dependency.
 *
 * Supported blocks:
 *   basic_table    3x3, tier 1
 *   advanced_table 5x5, tier 2
 *   elite_table    7x7, tier 3
 *   ultimate_table 9x9, tier 4
 *
 * These tables act as virtual crafting lanes for packaged providers. Inputs are
 * consumed by AE, while this adapter validates the matching table recipe and
 * returns the assembled result plus remaining items without inserting into the
 * table grid.
 */
public final class ExtendedCraftingTableAdapter implements VirtualCraftingAdapter {

    private static final String MOD_ID = "extendedcrafting";
    private static final ResourceLocation BASIC_TABLE_BLOCK = ecId("basic_table");
    private static final ResourceLocation ADVANCED_TABLE_BLOCK = ecId("advanced_table");
    private static final ResourceLocation ELITE_TABLE_BLOCK = ecId("elite_table");
    private static final ResourceLocation ULTIMATE_TABLE_BLOCK = ecId("ultimate_table");
    private static final ResourceLocation RECIPE_TYPE_ID = ecId("table");

    @Override
    public int priority() {
        return 100;
    }

    @Override
    public boolean recognizesMain(ServerLevel level, BlockPos pos, BlockEntity be) {
        return be != null
                && isExtendedCraftingLoaded()
                && tableSpec(be.getBlockState()) != null
                && EcReflection.isSupportedTable(be);
    }

    @Override
    @org.jetbrains.annotations.Nullable
    public net.minecraft.resources.ResourceLocation requiredAdapterId(ServerLevel level, BlockPos pos) {
        // Tier id matches the live block at the queried position. Higher-tier
        // packaged cores cover all lower tiers via MultiblockAdapterItem.covers,
        // so a single ec_ultimate core unlocks every EC table.
        var spec = tableSpec(level.getBlockState(pos));
        if (spec == null) {
            return AdapterIds.EC_BASIC;
        }
        return switch (spec.tier()) {
            case 1 -> AdapterIds.EC_BASIC;
            case 2 -> AdapterIds.EC_ADVANCED;
            case 3 -> AdapterIds.EC_ELITE;
            case 4 -> AdapterIds.EC_ULTIMATE;
            default -> AdapterIds.EC_BASIC;
        };
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
    public boolean canDispatch(ServerLevel level, BlockPos mainPos, Object handle) {
        // Virtual lanes have no machine state to check; the actual grid-empty
        // gate runs inside planVirtualWithBinding because EC tables share the
        // physical grid with player interaction.
        return true;
    }

    @Override
    @Nullable
    public DispatchPlan planWithBinding(ServerLevel level, BlockPos mainPos,
                                        IPatternDetails pattern, KeyCounter[] inputs,
                                        Object handle, IActionSource source) {
        // Virtual adapters never produce a real DispatchPlan; route through
        // planVirtualWithBinding instead.
        return null;
    }

    @Override
    @Nullable
    public VirtualCraftingResult planVirtualWithBinding(ServerLevel level, BlockPos mainPos,
                                                        IPatternDetails pattern, KeyCounter[] inputs,
                                                        Object handle, IActionSource source) {
        if (!(handle instanceof EcBindHandle bind)) {
            return null;
        }
        var spec = bind.spec();

        var be = level.getBlockEntity(mainPos);
        if (be == null || !recognizesMain(level, mainPos, be) || !spec.matches(be.getBlockState())) {
            return null;
        }

        var handler = EcReflection.getHandler(be);
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

        // Assign ingredients against exact AEItemKeys, not raw Item ids. This
        // preserves same-item variants with different DataComponents/NBT and
        // lets ambiguous ingredients backtrack instead of stealing the only
        // key a later ingredient can use.
        int k = bind.craftRatio();
        var perCraftStacks = assignIngredientsStrict(ingredients, available, k);
        if (perCraftStacks == null) {
            return null;
        }

        var planned = layoutGridFromIngredients(bind.recipe(), perCraftStacks, spec);
        if (planned == null) {
            return null;
        }

        var input = EcReflection.createTableInput(spec.gridSize(), stacksForPlan(spec.slots(), planned), spec.tier());
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
    public List<GenericStack> extractOutputs(ServerLevel level, BlockPos mainPos,
                                             AllowedOutputFilter filter,
                                             IActionSource source) {
        var be = level.getBlockEntity(mainPos);
        if (be == null || !recognizesMain(level, mainPos, be)) {
            return List.of();
        }

        var spec = tableSpec(be.getBlockState());
        var handler = EcReflection.getHandler(be);
        if (spec == null || !(handler instanceof IItemHandlerModifiable modifiable)
                || handler.getSlots() < spec.slots() || gridIsEmpty(handler, spec.slots())) {
            return List.of();
        }

        var input = EcReflection.createTableInput(spec.gridSize(), currentStacks(handler, spec.slots()), spec.tier());
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

    /**
     * One-shot recipe + K search executed at bind time. Walks every EC
     * table recipe whose result item id matches the pattern's primary
     * output, derives the batch multiplier {@code K = patternOutAmount /
     * resultCount}, and verifies each non-empty ingredient can be satisfied
     * by the pattern's declared inputs. The matched (recipe, K) plus the
     * pattern's output match mode are cached on the binding so plan-time
     * dispatch can skip the full recipe-table scan.
     *
     * <p>Strictness is split by the pattern's output match mode:
     * <ul>
     *   <li>{@link MatchMode#STRICT} (default for vanilla patterns):
     *       ingredient.test must accept the pattern's declared input stack
     *       with its DataComponents/NBT intact, and the pattern output key
     *       must equal {@code AEItemKey.of(recipe.result)} exactly. This
     *       locks in the precise recipe variant so plan time can trust the
     *       KeyCounter without re-running ingredient.test.</li>
     *   <li>{@link MatchMode#ID_ONLY} (AE2LT overload opt-in): bind only
     *       requires the ingredient to accept some item-id-equal variant
     *       (via {@link Ingredient#getItems()}). The runtime KeyCounter may
     *       carry a different NBT variant, so plan time falls back to a
     *       per-dispatch strict {@code ingredient.test} against the actual
     *       KeyCounter stack as the final anti-dup gate.</li>
     * </ul>
     *
     * <p>Anti-duplication invariant: neither path ever falls back to
     * {@code item.getDefaultInstance()} or any other representative stack
     * for the grid &mdash; the per-craft grid stack is always the exact
     * {@link KeyCounter} key (with its DataComponents/NBT) at plan time.
     */
    @Nullable
    private static EcBindHandle searchHandle(ServerLevel level, IPatternDetails pattern, TableSpec spec) {
        var patternOut = pattern.getOutputs().getFirst();
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

        boolean overload = outputMode(pattern, 0) == MatchMode.ID_ONLY;

        for (var holder : recipes(level)) {
            var recipe = holder.value();
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

            return new EcBindHandle(spec, recipe, (int) k, overload);
        }
        return null;
    }

    private static boolean canAssignIngredientsStrict(NonNullList<Ingredient> ingredients,
                                                      LinkedHashMap<AEItemKey, Long> available,
                                                      long k) {
        return assignIngredientKeys(ingredients, available, k,
                (ingredient, key) -> ingredient.test(key.toStack(1))) != null;
    }

    /**
     * Bind-time check for ID_ONLY output patterns: ingredient compatibility is
     * intentionally broadened to item id, while quantities are still tracked by
     * exact key so same-item variants do not erase each other before plan time.
     */
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

        var inputs = new ArrayList<ExtendedCraftingTableIngredientAssigner.Input<AEItemKey>>(available.size());
        for (var entry : available.entrySet()) {
            inputs.add(new ExtendedCraftingTableIngredientAssigner.Input<>(entry.getKey(), entry.getValue()));
        }
        return ExtendedCraftingTableIngredientAssigner.assign(nonEmptyIngredients, inputs, k, matcher);
    }

    private static boolean ingredientAcceptsItemId(Ingredient ing, Item item) {
        try {
            for (var candidate : ing.getItems()) {
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
        for (var ing : ingredients) {
            if (!ing.isEmpty()) {
                count++;
            }
        }
        return count;
    }

    /**
     * Places one ingredient's representative stack into a grid layout the
     * EC {@code TableCraftingInput.of} factory can trim and feed to
     * {@link Recipe#matches}.
     *
     * <p>For shaped recipes we walk the ingredient list in its native
     * {@code width × height} order and copy non-empty cells to the top-left
     * of the table grid. EC's input wrapper trims the empty borders, so the
     * absolute placement doesn't matter as long as the relative shape is
     * preserved.
     *
     * <p>For shapeless recipes we drop the stacks row-major into the top of
     * the grid; shapeless matching ignores positions.
     */
    @Nullable
    private static List<PlannedUnit> layoutGridFromIngredients(Object recipe,
                                                               List<ItemStack> perCraftStacks,
                                                               TableSpec spec) {
        var ingredients = ingredients(recipe);
        if (ingredients == null || ingredients.isEmpty()) {
            return null;
        }
        int gridSize = spec.gridSize();
        int slots = spec.slots();

        var width = EcReflection.recipeWidth(recipe);
        if (width == null) {
            if (perCraftStacks.size() > slots) {
                return null;
            }
            var planned = new ArrayList<PlannedUnit>(perCraftStacks.size());
            for (int i = 0; i < perCraftStacks.size(); i++) {
                int row = i / gridSize;
                int col = i % gridSize;
                if (row >= gridSize) {
                    return null;
                }
                planned.add(new PlannedUnit(row * gridSize + col, perCraftStacks.get(i).copy()));
            }
            return List.copyOf(planned);
        }

        if (width <= 0 || width > gridSize) {
            return null;
        }
        int height = (ingredients.size() + width - 1) / width;
        if (height > gridSize) {
            return null;
        }

        var planned = new ArrayList<PlannedUnit>(perCraftStacks.size());
        int nonEmptyIdx = 0;
        for (int relRow = 0; relRow < height; relRow++) {
            for (int relCol = 0; relCol < width; relCol++) {
                int ingIdx = relRow * width + relCol;
                if (ingIdx >= ingredients.size()) {
                    break;
                }
                var ing = ingredients.get(ingIdx);
                if (ing.isEmpty()) {
                    continue;
                }
                if (nonEmptyIdx >= perCraftStacks.size()) {
                    return null;
                }
                int slot = relRow * gridSize + relCol;
                planned.add(new PlannedUnit(slot, perCraftStacks.get(nonEmptyIdx++).copy()));
            }
        }
        if (nonEmptyIdx != perCraftStacks.size()) {
            return null;
        }
        return List.copyOf(planned);
    }

    @Nullable
    private static Object findMatchingRecipe(ServerLevel level, CraftingInput input) {
        for (var holder : recipes(level)) {
            var recipe = holder.value();
            if (recipeMatches(recipe, input, level)) {
                return recipe;
            }
        }
        return null;
    }

    private static boolean canApplyRemaining(IItemHandler handler, int gridSize, CraftingInput input,
                                             NonNullList<ItemStack> remaining) {
        if (remaining.size() < input.size()) {
            return false;
        }
        int top = EcReflection.top(input);
        int left = EcReflection.left(input);
        for (int y = 0; y < input.height(); y++) {
            for (int x = 0; x < input.width(); x++) {
                int inputIndex = x + y * input.width();
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
                if (!ItemStack.isSameItemSameComponents(current, remainder)) {
                    return false;
                }
                if (afterConsume + remainder.getCount() > current.getMaxStackSize()) {
                    return false;
                }
            }
        }
        return true;
    }

    private static void applyCraftRemainders(IItemHandlerModifiable handler, int gridSize, CraftingInput input,
                                             NonNullList<ItemStack> remaining) {
        int top = EcReflection.top(input);
        int left = EcReflection.left(input);
        for (int y = 0; y < input.height(); y++) {
            for (int x = 0; x < input.width(); x++) {
                int inputIndex = x + y * input.width();
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
                } else if (ItemStack.isSameItemSameComponents(after, remainder)) {
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
        return outputs.size() == 1 && outputs.getFirst().what() instanceof AEItemKey;
    }

    /**
     * Batch match: pattern output amount must equal the derived total
     * (K × per-craft result count). Used in the virtual-craft hot path
     * so the user can write any uniform N:N pattern (e.g.
     * {@code 8 stone → 8 brick}) and have it consumed in one push.
     */
    private static boolean outputMatchesBatch(IPatternDetails pattern, ItemStack result,
                                               long expectedTotalCount) {
        var outputs = pattern.getOutputs();
        if (outputs.size() != 1) {
            return false;
        }
        var expected = outputs.getFirst();
        if (!(expected.what() instanceof AEItemKey expectedKey)
                || expected.amount() != expectedTotalCount) {
            return false;
        }
        var actual = AEItemKey.of(result);
        return outputMode(pattern, 0) == MatchMode.ID_ONLY
                ? expectedKey.dropSecondary().equals(actual.dropSecondary())
                : expectedKey.equals(actual);
    }

    private static MatchMode outputMode(IPatternDetails pattern, int outputIndex) {
        if (pattern instanceof OverloadedProviderOnlyPatternDetails overload) {
            var outputs = overload.overloadPatternDetailsView().outputs();
            if (outputIndex >= 0 && outputIndex < outputs.size()) {
                return outputs.get(outputIndex).matchMode();
            }
        }
        return MatchMode.STRICT;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static boolean recipeMatches(Object recipe, CraftingInput input, ServerLevel level) {
        try {
            return recipe instanceof Recipe<?> r && ((Recipe) r).matches(input, level);
        } catch (RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    @Nullable
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static ItemStack assemble(Object recipe, CraftingInput input, ServerLevel level) {
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
    private static NonNullList<ItemStack> remainingItems(Object recipe, CraftingInput input) {
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
    private static List<RecipeHolder<?>> recipes(ServerLevel level) {
        return BuiltInRegistries.RECIPE_TYPE.getOptional(RECIPE_TYPE_ID)
                .map(type -> (List<RecipeHolder<?>>) (List<?>) level.getRecipeManager()
                        .getAllRecipesFor((RecipeType) type))
                .orElse(List.of());
    }

    @Nullable
    private static TableSpec tableSpec(BlockState state) {
        var id = blockId(state);
        if (id.equals(BASIC_TABLE_BLOCK)) {
            return new TableSpec(BASIC_TABLE_BLOCK, 3, 1);
        }
        if (id.equals(ADVANCED_TABLE_BLOCK)) {
            return new TableSpec(ADVANCED_TABLE_BLOCK, 5, 2);
        }
        if (id.equals(ELITE_TABLE_BLOCK)) {
            return new TableSpec(ELITE_TABLE_BLOCK, 7, 3);
        }
        if (id.equals(ULTIMATE_TABLE_BLOCK)) {
            return new TableSpec(ULTIMATE_TABLE_BLOCK, 9, 4);
        }
        return null;
    }

    private static boolean isExtendedCraftingLoaded() {
        return ModList.get().isLoaded(MOD_ID);
    }

    private static ResourceLocation blockId(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock());
    }

    private static ResourceLocation ecId(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }

    private record TableSpec(ResourceLocation blockId, int gridSize, int tier) {
        int slots() {
            return gridSize * gridSize;
        }

        boolean matches(BlockState state) {
            return ExtendedCraftingTableAdapter.blockId(state).equals(blockId);
        }
    }

    private record PlannedUnit(int slot, ItemStack stack) {
        PlannedUnit {
            Objects.requireNonNull(stack, "stack");
        }
    }

    /**
     * Bind-time cache populated by {@link #searchHandle}. Captures the
     * matched recipe, the batch multiplier K, and the pattern's output
     * match mode so plan-time dispatch can skip the recipe-table scan.
     */
    private record EcBindHandle(TableSpec spec, Object recipe, int craftRatio, boolean overload) {
    }

    private static final class EcReflection {
        private static final String BASIC_TABLE_CLASS =
                "com.blakebr0.extendedcrafting.tileentity.BasicTableTileEntity";
        private static final String ADVANCED_TABLE_CLASS =
                "com.blakebr0.extendedcrafting.tileentity.AdvancedTableTileEntity";
        private static final String ELITE_TABLE_CLASS =
                "com.blakebr0.extendedcrafting.tileentity.EliteTableTileEntity";
        private static final String ULTIMATE_TABLE_CLASS =
                "com.blakebr0.extendedcrafting.tileentity.UltimateTableTileEntity";
        private static final String BASE_INVENTORY_CLASS =
                "com.blakebr0.cucumber.tileentity.BaseInventoryTileEntity";
        private static final String TABLE_INPUT_CLASS =
                "com.blakebr0.extendedcrafting.api.TableCraftingInput";

        private static volatile boolean lookupDone;
        private static volatile @Nullable Set<Class<?>> tableClasses;
        private static volatile @Nullable Method getInventoryMethod;
        private static volatile @Nullable Method tableInputOfMethod;
        private static volatile @Nullable Method tableInputTopMethod;
        private static volatile @Nullable Method tableInputLeftMethod;
        private static volatile @Nullable Method shapedRecipeWidthMethod;

        static boolean isSupportedTable(Object o) {
            ensureLookup();
            if (tableClasses == null) {
                return false;
            }
            for (var clazz : tableClasses) {
                if (clazz.isInstance(o)) {
                    return true;
                }
            }
            return false;
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

        @Nullable
        static CraftingInput createTableInput(int size, List<ItemStack> stacks, int tier) {
            ensureLookup();
            if (tableInputOfMethod == null) {
                return null;
            }
            try {
                var value = tableInputOfMethod.invoke(null, size, size, stacks, tier);
                return value instanceof CraftingInput input ? input : null;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                return null;
            }
        }

        static int top(CraftingInput input) {
            ensureLookup();
            if (tableInputTopMethod == null) {
                return 0;
            }
            try {
                var value = tableInputTopMethod.invoke(input);
                return value instanceof Integer i ? i : 0;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                return 0;
            }
        }

        static int left(CraftingInput input) {
            ensureLookup();
            if (tableInputLeftMethod == null) {
                return 0;
            }
            try {
                var value = tableInputLeftMethod.invoke(input);
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

        private static void ensureLookup() {
            if (lookupDone) {
                return;
            }
            synchronized (EcReflection.class) {
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
            tableClasses = Set.of(
                    Class.forName(BASIC_TABLE_CLASS),
                    Class.forName(ADVANCED_TABLE_CLASS),
                    Class.forName(ELITE_TABLE_CLASS),
                    Class.forName(ULTIMATE_TABLE_CLASS));

            var baseInventoryClass = Class.forName(BASE_INVENTORY_CLASS);
            getInventoryMethod = baseInventoryClass.getMethod("getInventory");

            var tableInputClass = Class.forName(TABLE_INPUT_CLASS);
            tableInputOfMethod = tableInputClass.getMethod("of", int.class, int.class, List.class, int.class);
            tableInputTopMethod = tableInputClass.getMethod("top");
            tableInputLeftMethod = tableInputClass.getMethod("left");

            var shapedRecipeClass = Class.forName("com.blakebr0.extendedcrafting.crafting.recipe.ShapedTableRecipe");
            shapedRecipeWidthMethod = shapedRecipeClass.getMethod("getWidth");
        }
    }
}
