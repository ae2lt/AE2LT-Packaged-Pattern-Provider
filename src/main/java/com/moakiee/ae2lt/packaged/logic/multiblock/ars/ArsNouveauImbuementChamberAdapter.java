package com.moakiee.ae2lt.packaged.logic.multiblock.ars;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Predicate;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.common.capabilities.ForgeCapabilities;

import com.moakiee.ae2lt.packaged.logic.multiblock.AdapterRecipeTypes;
import com.moakiee.ae2lt.packaged.logic.multiblock.BlockCapabilities;
import com.moakiee.ae2lt.packaged.logic.multiblock.AdapterBlocks;
import net.minecraftforge.items.IItemHandler;

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
 * Runtime adapter for Ars Nouveau's Imbuement Chamber.
 *
 * <p>Ars checks nearby arcane pedestals when matching the recipe, but it never
 * consumes those pedestal items. This adapter therefore treats pedestal inputs
 * as temporary catalysts: dispatch them, let the chamber finish, then pull them
 * back to the provider together with the completed output.
 */
public final class ArsNouveauImbuementChamberAdapter implements MultiblockAdapter {

    private static final String MOD_ID = "ars_nouveau";
    private static final ResourceLocation IMBUEMENT_BLOCK = arsId("imbuement_chamber");
    private static final ResourceLocation PEDESTAL_BLOCK = arsId("arcane_pedestal");
    private static final ResourceLocation ARCANE_PLATFORM_BLOCK = arsId("arcane_platform");
    private static final ResourceLocation RECIPE_TYPE_ID = arsId("imbuement");
    private static final int PEDESTAL_RADIUS = 1;
    private static final int MAX_INPUT_UNITS = 128;

    @Override
    public int priority() {
        return 100;
    }

    @Override
    public boolean recognizesMain(ServerLevel level, BlockPos pos, BlockEntity be) {
        return be != null
                && isArsLoaded()
                && blockId(be.getBlockState()).equals(IMBUEMENT_BLOCK)
                && be instanceof Container
                && ArsReflection.isImbuementTile(be);
    }

    @Override
    public ResourceLocation requiredAdapterId(ServerLevel level, BlockPos pos) {
        return AdapterIds.ARS_IMBUEMENT;
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
        return new BindingResult(new ImbuementBindHandle(recipe), BindingMode.REAL);
    }

    @Override
    public boolean canDispatch(ServerLevel level, BlockPos mainPos, Object handle) {
        if (!(handle instanceof ImbuementBindHandle bind)) {
            return false;
        }
        var be = level.getBlockEntity(mainPos);
        if (be == null || !recognizesMain(level, mainPos, be) || !(be instanceof Container chamber)) {
            return false;
        }
        if (!containerEmpty(chamber)) {
            return false;
        }

        var pedestalIngredients = ArsReflection.getPedestalItems(bind.recipe());
        if (pedestalIngredients == null) {
            return false;
        }
        var pedestals = findEmptyPedestals(level, mainPos);
        return pedestals != null && pedestals.size() >= pedestalIngredients.size();
    }

    @Override
    @Nullable
    public DispatchPlan planWithBinding(ServerLevel level, BlockPos mainPos,
                                        IPatternDetails pattern, KeyCounter[] inputs,
                                        Object handle, IActionSource source) {
        if (!(handle instanceof ImbuementBindHandle bind)) {
            return null;
        }

        var be = level.getBlockEntity(mainPos);
        if (be == null || !recognizesMain(level, mainPos, be) || !(be instanceof Container chamber)
                || !containerEmpty(chamber)) {
            return null;
        }

        var inputIngredient = ArsReflection.getInput(bind.recipe());
        var pedestalIngredients = ArsReflection.getPedestalItems(bind.recipe());
        if (inputIngredient == null || pedestalIngredients == null) {
            return null;
        }

        var pedestals = findEmptyPedestals(level, mainPos);
        if (pedestals == null || pedestals.size() < pedestalIngredients.size()) {
            return null;
        }

        var units = expandInputUnits(inputs);
        if (units == null) {
            return null;
        }

        var match = assignInputsToRecipe(units, inputIngredient, pedestalIngredients);
        if (match == null) {
            return null;
        }

        var targets = new ArrayList<TargetSlot>(match.pedestals().size() + 1);
        for (int i = 0; i < match.pedestals().size(); i++) {
            var unit = match.pedestals().get(i);
            var pedestalPos = pedestals.get(i);
            targets.add(new TargetSlot(
                    level,
                    pedestalPos,
                    null,
                    List.of(unit.toGenericStack()),
                    InsertionStrategy.CUSTOM,
                    pedestalInserter(level, pedestalPos, unit)));
        }

        targets.add(new TargetSlot(
                level,
                mainPos,
                null,
                List.of(match.reagent().toGenericStack()),
                InsertionStrategy.CUSTOM,
                chamberInserter(level, mainPos, match.reagent(), inputIngredient, pedestalIngredients)));

        return new DispatchPlan(List.copyOf(targets), null);
    }

    @Override
    public List<GenericStack> extractOutputs(ServerLevel level, BlockPos mainPos,
                                             AllowedOutputFilter filter, IActionSource source) {
        var be = level.getBlockEntity(mainPos);
        if (be == null || !recognizesMain(level, mainPos, be) || !(be instanceof Container chamber)) {
            return List.of();
        }

        var stack = chamber.getItem(0);
        if (stack.isEmpty()) {
            return List.of();
        }

        var key = AEItemKey.of(stack);
        if (!filter.matches(key)) {
            return List.of();
        }

        var completed = findCompletedRecipe(level, mainPos, stack);
        if (completed == null) {
            return List.of();
        }

        var handler = itemHandler(level, mainPos);
        if (handler == null || handler.getSlots() < 1) {
            return List.of();
        }

        var results = new ArrayList<GenericStack>(completed.pedestals().size() + 1);
        var extracted = extractSlot(handler, 0);
        if (extracted.isEmpty()) {
            return List.of();
        }
        results.add(new GenericStack(AEItemKey.of(extracted), extracted.getCount()));

        for (var pedestal : completed.pedestals()) {
            var container = pedestalContainer(level, pedestal.pos());
            if (container == null) {
                continue;
            }
            var pedestalStack = container.getItem(0);
            if (pedestalStack.isEmpty() || !pedestal.matches(pedestalStack)) {
                continue;
            }
            var pedestalHandler = itemHandler(level, pedestal.pos());
            if (pedestalHandler == null || pedestalHandler.getSlots() < 1) {
                continue;
            }
            var returned = extractSlot(pedestalHandler, 0);
            if (!returned.isEmpty()) {
                results.add(new GenericStack(AEItemKey.of(returned), returned.getCount()));
            }
        }

        return List.copyOf(results);
    }

    @Nullable
    private static Object findCandidateRecipe(ServerLevel level, IPatternDetails pattern) {
        for (var recipe : recipes(level)) {
            var result = resultItem(recipe, level);
            if (result.isEmpty() || !outputMatches(pattern, result)) {
                continue;
            }
            var inputIngredient = ArsReflection.getInput(recipe);
            var pedestalIngredients = ArsReflection.getPedestalItems(recipe);
            if (inputIngredient == null || pedestalIngredients == null) {
                continue;
            }
            if (!patternInputsMatchRecipe(pattern, inputIngredient, pedestalIngredients)) {
                continue;
            }
            return recipe;
        }
        return null;
    }

    private static boolean patternInputsMatchRecipe(IPatternDetails pattern,
                                                    Ingredient inputIngredient,
                                                    List<Ingredient> pedestalIngredients) {
        var units = patternInputUnits(pattern);
        return units != null && assignInputsToRecipe(units, inputIngredient, pedestalIngredients) != null;
    }

    @Nullable
    private static RecipeMatch assignInputsToRecipe(List<PlannedUnit> units,
                                                    Ingredient inputIngredient,
                                                    List<Ingredient> pedestalIngredients) {
        List<Predicate<PlannedUnit>> pedestalPredicates = pedestalIngredients.stream()
                .<Predicate<PlannedUnit>>map(ingredient -> unit -> ingredient.test(unit.stack()))
                .toList();

        var match = ArsImbuementInputMatcher.match(
                units,
                unit -> inputIngredient.test(unit.stack()),
                pedestalPredicates);
        if (match == null) {
            return null;
        }
        return new RecipeMatch(match.reagent(), match.pedestals());
    }

    @Nullable
    private static CompletedRecipe findCompletedRecipe(ServerLevel level, BlockPos mainPos, ItemStack outputStack) {
        for (var recipe : recipes(level)) {
            var result = resultItem(recipe, level);
            if (!sameStackAndCount(outputStack, result)) {
                continue;
            }
            var pedestalIngredients = ArsReflection.getPedestalItems(recipe);
            if (pedestalIngredients == null) {
                continue;
            }
            var pedestals = assignPedestalsForExtraction(level, mainPos, pedestalIngredients);
            if (pedestals != null) {
                return new CompletedRecipe(pedestals);
            }
        }
        return null;
    }

    @Nullable
    private static List<PedestalReturn> assignPedestalsForExtraction(ServerLevel level,
                                                                     BlockPos mainPos,
                                                                     List<Ingredient> ingredients) {
        var contents = currentPedestalContents(level, mainPos);
        if (contents == null || contents.size() != ingredients.size()) {
            return null;
        }
        var used = new boolean[contents.size()];
        var returns = new ArrayList<PedestalReturn>(ingredients.size());
        if (!assignPedestalForExtraction(contents, ingredients, used, returns, 0)) {
            return null;
        }
        return List.copyOf(returns);
    }

    private static boolean assignPedestalForExtraction(List<PedestalContent> contents,
                                                       List<Ingredient> ingredients,
                                                       boolean[] used,
                                                       List<PedestalReturn> returns,
                                                       int ingredientIndex) {
        if (ingredientIndex == ingredients.size()) {
            return true;
        }

        var ingredient = ingredients.get(ingredientIndex);
        for (int contentIndex = 0; contentIndex < contents.size(); contentIndex++) {
            if (used[contentIndex]) {
                continue;
            }
            var content = contents.get(contentIndex);
            if (!ingredient.test(content.stack())) {
                continue;
            }

            used[contentIndex] = true;
            returns.add(new PedestalReturn(content.pos(), ingredient));
            if (assignPedestalForExtraction(contents, ingredients, used, returns, ingredientIndex + 1)) {
                return true;
            }
            returns.remove(returns.size() - 1);
            used[contentIndex] = false;
        }
        return false;
    }

    @Nullable
    private static List<PedestalContent> currentPedestalContents(ServerLevel level, BlockPos mainPos) {
        var result = new ArrayList<PedestalContent>();
        var min = mainPos.offset(-PEDESTAL_RADIUS, -PEDESTAL_RADIUS, -PEDESTAL_RADIUS);
        var max = mainPos.offset(PEDESTAL_RADIUS, PEDESTAL_RADIUS, PEDESTAL_RADIUS);

        for (var mutablePos : BlockPos.betweenClosed(min, max)) {
            var pos = mutablePos.immutable();
            if (level.isOutsideBuildHeight(pos)) {
                continue;
            }
            if (!level.isLoaded(pos)) {
                return null;
            }
            var container = pedestalContainer(level, pos);
            if (container == null) {
                continue;
            }
            var stack = container.getItem(0);
            if (!stack.isEmpty()) {
                result.add(new PedestalContent(pos, stack.copy()));
            }
        }
        return result;
    }

    @Nullable
    private static List<BlockPos> findEmptyPedestals(ServerLevel level, BlockPos mainPos) {
        var result = new ArrayList<BlockPos>();
        var min = mainPos.offset(-PEDESTAL_RADIUS, -PEDESTAL_RADIUS, -PEDESTAL_RADIUS);
        var max = mainPos.offset(PEDESTAL_RADIUS, PEDESTAL_RADIUS, PEDESTAL_RADIUS);

        for (var mutablePos : BlockPos.betweenClosed(min, max)) {
            var pos = mutablePos.immutable();
            if (level.isOutsideBuildHeight(pos)) {
                continue;
            }
            if (!level.isLoaded(pos)) {
                return null;
            }
            var container = pedestalContainer(level, pos);
            if (container == null) {
                continue;
            }
            if (!containerEmpty(container)) {
                return null;
            }
            result.add(pos);
        }
        return result;
    }

    @Nullable
    private static Container pedestalContainer(ServerLevel level, BlockPos pos) {
        if (!level.isLoaded(pos)) {
            return null;
        }
        var be = level.getBlockEntity(pos);
        if (be instanceof Container container
                && isPedestalBlock(be.getBlockState())
                && ArsReflection.isArcanePedestal(be)) {
            return container;
        }
        return null;
    }

    @Nullable
    private static IItemHandler itemHandler(ServerLevel level, BlockPos pos) {
        return BlockCapabilities.find(level, pos, ForgeCapabilities.ITEM_HANDLER, null);
    }

    static ItemStack extractSlot(IItemHandler handler, int slot) {
        var stack = handler.getStackInSlot(slot);
        if (stack.isEmpty()) {
            return ItemStack.EMPTY;
        }
        return handler.extractItem(slot, stack.getCount(), false);
    }

    private static BiFunction<GenericStack, Actionable, Long> pedestalInserter(
            ServerLevel level, BlockPos pos, PlannedUnit unit) {
        return (stack, mode) -> {
            if (!matchesPlannedStack(stack, unit)) {
                return 0L;
            }
            var container = pedestalContainer(level, pos);
            if (container == null || !containerEmpty(container)) {
                return 0L;
            }
            if (mode == Actionable.MODULATE) {
                container.setItem(0, unit.stack());
                container.setChanged();
            }
            return 1L;
        };
    }

    private static BiFunction<GenericStack, Actionable, Long> chamberInserter(
            ServerLevel level, BlockPos pos, PlannedUnit unit,
            Ingredient inputIngredient, List<Ingredient> pedestalIngredients) {
        return (stack, mode) -> {
            if (!matchesPlannedStack(stack, unit)) {
                return 0L;
            }
            var be = level.getBlockEntity(pos);
            if (be == null
                    || !blockId(be.getBlockState()).equals(IMBUEMENT_BLOCK)
                    || !ArsReflection.isImbuementTile(be)
                    || !(be instanceof Container chamber)
                    || !containerEmpty(chamber)) {
                return 0L;
            }

            if (mode == Actionable.MODULATE) {
                if (!inputIngredient.test(unit.stack())
                        || assignPedestalsForExtraction(level, pos, pedestalIngredients) == null) {
                    return 0L;
                }
                chamber.setItem(0, unit.stack());
                chamber.setChanged();
            }
            return 1L;
        };
    }

    @Nullable
    private static List<PlannedUnit> expandInputUnits(KeyCounter[] inputs) {
        var units = new ArrayList<PlannedUnit>();
        long total = 0;
        for (var counter : inputs) {
            for (var entry : counter) {
                if (!(entry.getKey() instanceof AEItemKey itemKey)) {
                    return null;
                }
                long amount = entry.getLongValue();
                if (amount <= 0) {
                    continue;
                }
                total += amount;
                if (total > MAX_INPUT_UNITS) {
                    return null;
                }
                for (long i = 0; i < amount; i++) {
                    units.add(new PlannedUnit(itemKey));
                }
            }
        }
        return List.copyOf(units);
    }

    @Nullable
    private static List<PlannedUnit> patternInputUnits(IPatternDetails pattern) {
        var units = new ArrayList<PlannedUnit>();
        long total = 0;
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
            total += amount;
            if (total > MAX_INPUT_UNITS) {
                return null;
            }
            for (long i = 0; i < amount; i++) {
                units.add(new PlannedUnit(itemKey));
            }
        }
        return units.isEmpty() ? null : List.copyOf(units);
    }

    private static ItemStack resultItem(Object recipe, ServerLevel level) {
        var output = ArsReflection.output(recipe);
        if (output != null && !output.isEmpty()) {
            return output;
        }
        if (!(recipe instanceof Recipe<?> typedRecipe)) {
            return ItemStack.EMPTY;
        }
        try {
            return typedRecipe.getResultItem(level.registryAccess()).copy();
        } catch (RuntimeException | LinkageError ignored) {
            return ItemStack.EMPTY;
        }
    }

    private static boolean sameStackAndCount(ItemStack left, ItemStack right) {
        if (left.isEmpty() || right.isEmpty() || left.getCount() != right.getCount()) {
            return false;
        }
        return AEItemKey.of(left).equals(AEItemKey.of(right));
    }

    private static boolean outputMatches(IPatternDetails pattern, ItemStack result) {
        var outputs = pattern.getOutputs();
        if (outputs.length != 1) {
            return false;
        }

        var expected = outputs[0];
        if (!(expected.what() instanceof AEItemKey expectedKey) || expected.amount() != result.getCount()) {
            return false;
        }

        var actual = AEItemKey.of(result);
        return OverloadPatternSemantics.isIdOnlyOutput(pattern, 0)
                ? expectedKey.dropSecondary().equals(actual.dropSecondary())
                : expectedKey.equals(actual);
    }

    private static boolean hasSingleItemOutput(IPatternDetails pattern) {
        var outputs = pattern.getOutputs();
        return outputs.length == 1 && outputs[0].what() instanceof AEItemKey;
    }

    private static boolean matchesPlannedStack(GenericStack stack, PlannedUnit unit) {
        return stack.amount() == 1 && unit.key().equals(stack.what());
    }

    private static boolean containerEmpty(Container container) {
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            if (!container.getItem(slot).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private static List<Recipe<?>> recipes(ServerLevel level) {
        var registryRecipes = ArsReflection.getImbuementRecipes();
        if (!registryRecipes.isEmpty()) {
            return registryRecipes;
        }

        var type = AdapterRecipeTypes.find(RECIPE_TYPE_ID);
        if (type == null) {
            return List.of();
        }
        return (List<Recipe<?>>) (List<?>) level.getRecipeManager()
                .getAllRecipesFor((RecipeType) type);
    }


    private static boolean isArsLoaded() {
        return ModList.get().isLoaded(MOD_ID);
    }

    private static ResourceLocation blockId(BlockState state) { return AdapterBlocks.idOf(state); }

    private static boolean isPedestalBlock(BlockState state) {
        var id = blockId(state);
        return id.equals(PEDESTAL_BLOCK) || id.equals(ARCANE_PLATFORM_BLOCK);
    }

    private static ResourceLocation arsId(String path) {
        return new ResourceLocation(MOD_ID, path);
    }

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

    private record RecipeMatch(PlannedUnit reagent, List<PlannedUnit> pedestals) {
    }

    private record CompletedRecipe(List<PedestalReturn> pedestals) {
    }

    private record PedestalContent(BlockPos pos, ItemStack stack) {
    }

    private record PedestalReturn(BlockPos pos, Ingredient ingredient) {
        boolean matches(ItemStack stack) {
            return ingredient.test(stack);
        }
    }

    private record ImbuementBindHandle(Object recipe) {
    }

    private static final class ArsReflection {
        private static final String IMBUEMENT_TILE_CLASS =
                "com.hollingsworth.arsnouveau.common.block.tile.ImbuementTile";
        private static final String ARCANE_PEDESTAL_TILE_CLASS =
                "com.hollingsworth.arsnouveau.common.block.tile.ArcanePedestalTile";
        private static final String IMBUEMENT_REGISTRY_CLASS =
                "com.hollingsworth.arsnouveau.api.registry.ImbuementRecipeRegistry";

        private static volatile boolean lookupDone;
        private static volatile @Nullable Class<?> imbuementTileClass;
        private static volatile @Nullable Class<?> arcanePedestalTileClass;
        private static volatile @Nullable Field registryInstanceField;
        private static volatile @Nullable Method getRecipesMethod;
        private static volatile @Nullable Method getInputMethod;
        private static volatile @Nullable Method getPedestalItemsMethod;
        // 1.20.x exposes these as public fields instead of getter methods.
        private static volatile @Nullable Field inputField;
        private static volatile @Nullable Field pedestalItemsField;
        private static volatile @Nullable Field outputField;

        static boolean isImbuementTile(Object value) {
            ensureLookup();
            return imbuementTileClass != null && imbuementTileClass.isInstance(value);
        }

        static boolean isArcanePedestal(Object value) {
            ensureLookup();
            return arcanePedestalTileClass != null && arcanePedestalTileClass.isInstance(value);
        }

        static List<Recipe<?>> getImbuementRecipes() {
            if (!isArsLoaded()) {
                return List.of();
            }
            ensureLookup();
            if (registryInstanceField == null || getRecipesMethod == null) {
                return List.of();
            }
            try {
                Object registry = registryInstanceField.get(null);
                Object value = getRecipesMethod.invoke(registry);
                if (!(value instanceof Iterable<?> iterable)) {
                    return List.of();
                }
                var recipes = new ArrayList<Recipe<?>>();
                for (var item : iterable) {
                    if (item instanceof Recipe<?> holder) {
                        recipes.add(holder);
                    }
                }
                return List.copyOf(recipes);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                return List.of();
            }
        }

        /** Output stack; vanilla {@code getResultItem} is overridden empty on 1.20.x. */
        @Nullable
        static ItemStack output(Object recipe) {
            ensureLookup();
            var field = outputField;
            if (field == null) {
                return null;
            }
            try {
                return field.get(recipe) instanceof ItemStack stack ? stack.copy() : null;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                return null;
            }
        }

        @Nullable
        static Ingredient getInput(Object recipe) {
            ensureLookup();
            if (getInputMethod != null) {
                try {
                    var value = getInputMethod.invoke(recipe);
                    if (value instanceof Ingredient ingredient) {
                        return ingredient;
                    }
                } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                    // fall through to the field fallback
                }
            }
            var field = inputField;
            if (field == null) {
                return null;
            }
            try {
                return field.get(recipe) instanceof Ingredient ingredient ? ingredient : null;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                return null;
            }
        }

        @Nullable
        @SuppressWarnings("unchecked")
        static List<Ingredient> getPedestalItems(Object recipe) {
            ensureLookup();
            if (getPedestalItemsMethod != null) {
                try {
                    var value = getPedestalItemsMethod.invoke(recipe);
                    if (value instanceof List<?> list) {
                        var result = new ArrayList<Ingredient>(list.size());
                        for (var item : list) {
                            if (!(item instanceof Ingredient ingredient)) {
                                return null;
                            }
                            result.add(ingredient);
                        }
                        return List.copyOf(result);
                    }
                } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                    // fall through to the field fallback
                }
            }
            var field = pedestalItemsField;
            if (field == null) {
                return null;
            }
            try {
                Object value = field.get(recipe);
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
                return List.copyOf(result);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                return null;
            }
        }

        private static void ensureLookup() {
            if (lookupDone) {
                return;
            }
            synchronized (ArsReflection.class) {
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
            imbuementTileClass = Class.forName(IMBUEMENT_TILE_CLASS);
            arcanePedestalTileClass = Class.forName(ARCANE_PEDESTAL_TILE_CLASS);

            // Steps below resolve independently so a missing member on one mod
            // version cannot disable the remaining lookups.
            try {
                var registryClass = Class.forName(IMBUEMENT_REGISTRY_CLASS);
                registryInstanceField = registryClass.getField("INSTANCE");
                getRecipesMethod = registryClass.getMethod("getRecipes");
            } catch (ReflectiveOperationException ignored) {
                registryInstanceField = null;
                getRecipesMethod = null;
            }

            try {
                var recipeClass = Class.forName(
                        "com.hollingsworth.arsnouveau.common.crafting.recipes.ImbuementRecipe");
                getInputMethod = recipeClass.getMethod("getInput");
            } catch (ReflectiveOperationException ignored) {
                getInputMethod = null;
            }
            try {
                var recipeClass = Class.forName(
                        "com.hollingsworth.arsnouveau.common.crafting.recipes.ImbuementRecipe");
                getPedestalItemsMethod = recipeClass.getMethod("getPedestalItems");
            } catch (ReflectiveOperationException ignored) {
                getPedestalItemsMethod = null;
            }

            try {
                var recipeClass = Class.forName(
                        "com.hollingsworth.arsnouveau.common.crafting.recipes.ImbuementRecipe");
                inputField = recipeClass.getField("input");
                pedestalItemsField = recipeClass.getField("pedestalItems");
                outputField = recipeClass.getField("output");
            } catch (ReflectiveOperationException ignored) {
                inputField = null;
                pedestalItemsField = null;
                outputField = null;
            }
        }
    }
}
