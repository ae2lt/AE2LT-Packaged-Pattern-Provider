package com.moakiee.ae2lt.packaged.logic.multiblock.de;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Predicate;

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

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;

import com.moakiee.ae2lt.logic.AllowedOutputFilter;
import com.moakiee.ae2lt.packaged.logic.multiblock.DispatchPlan;
import com.moakiee.ae2lt.packaged.logic.multiblock.InsertionStrategy;
import com.moakiee.ae2lt.packaged.logic.multiblock.MultiblockAdapter;
import com.moakiee.ae2lt.packaged.logic.multiblock.ReflectionSupport;
import com.moakiee.ae2lt.packaged.logic.multiblock.TargetSlot;
import com.moakiee.ae2lt.packaged.logic.multiblock.binding.BindingMode;
import com.moakiee.ae2lt.packaged.logic.multiblock.binding.BindingResult;
import com.moakiee.thunderbolt.ae2.overload.model.MatchMode;
import com.moakiee.thunderbolt.ae2.overload.pattern.OverloadedProviderOnlyPatternDetails;

/**
 * Runtime adapter for Draconic Evolution's Fusion Crafting multiblock.
 * Pure reflection — DE is not a compile dependency.
 */
public final class DraconicFusionCraftingAdapter implements MultiblockAdapter {

    private static final String MOD_ID = "draconicevolution";
    private static final ResourceLocation CRAFTING_CORE_BLOCK = deId("crafting_core");
    private static final int MAX_INPUT_UNITS = 128;

    @Override
    public int priority() {
        return 100;
    }

    @Override
    public boolean recognizesMain(ServerLevel level, BlockPos pos, BlockEntity be) {
        return be != null && isDeLoaded() && blockId(be.getBlockState()).equals(CRAFTING_CORE_BLOCK);
    }

    @Override
    public ResourceLocation requiredAdapterId(ServerLevel level, BlockPos pos) {
        return com.moakiee.ae2lt.packaged.item.AdapterIds.DE_FUSION;
    }

    @Override
    @Nullable
    public BindingResult bind(ServerLevel level, BlockPos mainPos, IPatternDetails pattern) {
        var be = level.getBlockEntity(mainPos);
        if (be == null || !recognizesMain(level, mainPos, be)) return null;
        if (!hasSingleItemOutput(pattern)) return null;

        var recipe = findCandidateRecipe(level, pattern);
        if (recipe == null) return null;
        return new BindingResult(new FusionBindHandle(recipe), BindingMode.REAL);
    }

    @Override
    public boolean canDispatch(ServerLevel level, BlockPos mainPos, Object handle) {
        if (!(handle instanceof FusionBindHandle bind)) return false;
        var be = level.getBlockEntity(mainPos);
        if (be == null || !recognizesMain(level, mainPos, be)) return false;
        if (DEReflection.isCrafting(be)) return false;

        var outputStack = DEReflection.getOutputStack(be);
        if (outputStack == null) return false;

        // Output slot may already hold a stackable result; recompute expected.
        var expected = DEReflection.getResultItem(bind.recipe(), level);
        if (expected == null || expected.isEmpty()) return false;
        if (!outputStack.isEmpty()) {
            if (!ItemStack.isSameItemSameComponents(outputStack, expected)
                    || outputStack.getCount() + expected.getCount() > expected.getMaxStackSize()) {
                return false;
            }
        }
        var injectors = DEReflection.getInjectors(be, level);
        if (injectors == null) return false;

        var fusionIngredients = DEReflection.getFusionIngredients(bind.recipe());
        var recipeTierIndex = DEReflection.getRecipeTierIndex(bind.recipe());
        if (fusionIngredients == null || recipeTierIndex < 0) return false;
        return hasAvailableInjectors(injectors, recipeTierIndex, fusionIngredients.size());
    }

    @Override
    @Nullable
    public DispatchPlan planWithBinding(ServerLevel level, BlockPos mainPos,
                                        IPatternDetails pattern, KeyCounter[] inputs,
                                        Object handle, IActionSource source) {
        if (!(handle instanceof FusionBindHandle bind)) return null;
        var be = level.getBlockEntity(mainPos);
        if (be == null) return null;

        var units = expandInputUnits(inputs);
        if (units == null || units.isEmpty()) return null;

        var injectors = DEReflection.getInjectors(be, level);
        if (injectors == null) return null;

        var match = assignInputsToRecipe(bind.recipe(), level, units, injectors);
        if (match == null) return null;

        var targets = new ArrayList<TargetSlot>(match.injectorAssignments().size() + 1);
        targets.add(new TargetSlot(
                level, mainPos, null,
                List.of(match.catalyst().toGenericStack()),
                InsertionStrategy.CUSTOM,
                coreInserter(level, mainPos, match.catalyst())));

        for (var assignment : match.injectorAssignments()) {
            targets.add(new TargetSlot(
                    level, assignment.injectorPos(), null,
                    List.of(assignment.unit().toGenericStack()),
                    InsertionStrategy.CUSTOM,
                    injectorInserter(level, mainPos, assignment.injectorPos(), assignment.unit())));
        }

        return new DispatchPlan(List.copyOf(targets), () -> DEReflection.startCraftIfIdle(be));
    }

    @Override
    public List<GenericStack> extractOutputs(ServerLevel level, BlockPos mainPos,
                                             AllowedOutputFilter filter,
                                             IActionSource source) {
        var be = level.getBlockEntity(mainPos);
        if (be == null || !recognizesMain(level, mainPos, be)) return List.of();
        if (DEReflection.isCrafting(be)) return List.of();

        var stack = DEReflection.getOutputStack(be);
        if (stack == null || stack.isEmpty()) return List.of();

        var key = AEItemKey.of(stack);
        if (!allowsAutoReturn(level, mainPos, filter, key)) return List.of();

        var extracted = DEReflection.extractOutput(be);
        if (extracted.isEmpty()) return List.of();

        return List.of(new GenericStack(AEItemKey.of(extracted), extracted.getCount()));
    }

    // ===== Recipe matching =====

    /**
     * Bind-time search: locks the recipe by pattern.outputs alone, without
     * needing the runtime KeyCounter inputs or injector tiers.
     */
    @Nullable
    private static Object findCandidateRecipe(ServerLevel level, IPatternDetails pattern) {
        var recipes = DEReflection.getFusionRecipes(level);
        if (recipes == null) return null;

        for (var holder : recipes) {
            var recipe = holder.value();
            var catalyst = DEReflection.getCatalyst(recipe);
            var fusionIngredients = DEReflection.getFusionIngredients(recipe);
            var recipeTierIndex = DEReflection.getRecipeTierIndex(recipe);
            var resultItem = DEReflection.getResultItem(recipe, level);
            if (catalyst == null || fusionIngredients == null
                    || recipeTierIndex < 0 || resultItem == null || resultItem.isEmpty()) {
                continue;
            }
            if (!outputMatches(pattern, resultItem)) continue;
            return recipe;
        }
        return null;
    }

    /**
     * Per-push input assignment against the recipe locked in by {@link #bind}.
     * Splits inputs into catalyst units and ingredient units, then matches
     * ingredient units to injectors that meet the recipe tier.
     */
    @Nullable
    private RecipeMatch assignInputsToRecipe(Object recipe, ServerLevel level,
                                              List<PlannedUnit> units,
                                              List<InjectorInfo> injectors) {
        var catalyst = DEReflection.getCatalyst(recipe);
        var fusionIngredients = DEReflection.getFusionIngredients(recipe);
        var recipeTierIndex = DEReflection.getRecipeTierIndex(recipe);
        var resultItem = DEReflection.getResultItem(recipe, level);
        if (catalyst == null || fusionIngredients == null
                || recipeTierIndex < 0 || resultItem == null || resultItem.isEmpty()) {
            return null;
        }

        int catalystCount = DEReflection.getCatalystCount(recipe);
        if (catalystCount <= 0) catalystCount = 1;
        final int requiredCatalystCount = catalystCount;

        if (units.size() != requiredCatalystCount + fusionIngredients.size()) return null;

        var unitKeys = new ArrayList<AEItemKey>(units.size());
        for (var unit : units) {
            unitKeys.add(unit.key());
        }

        var ingredientMatchers = new ArrayList<Predicate<AEItemKey>>(fusionIngredients.size());
        for (var fi : fusionIngredients) {
            ingredientMatchers.add(key -> fi.ingredient().test(key.toStack(1)));
        }

        var inputMatch = DraconicFusionInputMatcher.match(
                unitKeys,
                requiredCatalystCount,
                key -> catalyst.test(key.toStack(requiredCatalystCount)),
                ingredientMatchers);
        if (inputMatch == null) return null;

        var assignments = assignIngredientsToInjectors(
                inputMatch.ingredients(), injectors, recipeTierIndex);
        if (assignments == null) return null;

        var catalystKey = inputMatch.catalystKey();
        var catalystPlanned = new PlannedUnit(catalystKey, requiredCatalystCount);

        return new RecipeMatch(catalystPlanned, assignments, resultItem);
    }

    @Nullable
    private List<InjectorAssignment> assignIngredientsToInjectors(
            List<AEItemKey> ingredientKeys,
            List<InjectorInfo> injectors,
            int recipeTierIndex) {
        var available = selectAvailableInjectors(injectors, recipeTierIndex, ingredientKeys.size());
        if (available.isEmpty()) return null;

        var assignments = new ArrayList<InjectorAssignment>(ingredientKeys.size());
        var selected = available.orElseThrow();
        for (int i = 0; i < ingredientKeys.size(); i++) {
            assignments.add(new InjectorAssignment(
                    selected.get(i).pos(),
                    new PlannedUnit(ingredientKeys.get(i), 1)));
        }
        return assignments;
    }

    private static boolean hasAvailableInjectors(
            List<InjectorInfo> injectors,
            int recipeTierIndex,
            int requiredCount) {
        return selectAvailableInjectors(injectors, recipeTierIndex, requiredCount).isPresent();
    }

    private static Optional<List<InjectorInfo>> selectAvailableInjectors(
            List<InjectorInfo> injectors,
            int recipeTierIndex,
            int requiredCount) {
        var candidates = new ArrayList<DraconicFusionInjectorMatcher.Candidate<InjectorInfo>>();
        for (var inj : injectors) {
            candidates.add(new DraconicFusionInjectorMatcher.Candidate<>(
                    inj, inj.tierIndex(), inj.empty()));
        }
        return DraconicFusionInjectorMatcher.selectAvailableTargets(
                candidates, recipeTierIndex, requiredCount);
    }

    // ===== Inserters =====

    private static BiFunction<GenericStack, Actionable, Long> coreInserter(
            ServerLevel level, BlockPos pos, PlannedUnit unit) {
        return (stack, mode) -> {
            if (stack.amount() != unit.amount() || !unit.key().equals(stack.what())) return 0L;
            var be = level.getBlockEntity(pos);
            if (be == null || !blockId(be.getBlockState()).equals(CRAFTING_CORE_BLOCK)) return 0L;
            if (DEReflection.isCrafting(be)) return 0L;
            var current = DEReflection.getCatalystStack(be);
            if (current == null || !current.isEmpty()) return 0L;
            if (mode == Actionable.MODULATE) {
                DEReflection.setCatalystStack(be, unit.stack());
            }
            return (long) unit.amount();
        };
    }

    private static BiFunction<GenericStack, Actionable, Long> injectorInserter(
            ServerLevel level, BlockPos corePos, BlockPos pos, PlannedUnit unit) {
        return (stack, mode) -> {
            if (stack.amount() != 1 || !unit.key().equals(stack.what())) return 0L;
            var core = level.getBlockEntity(corePos);
            if (core == null || !blockId(core.getBlockState()).equals(CRAFTING_CORE_BLOCK)
                    || DEReflection.isCrafting(core)) {
                return 0L;
            }
            var be = level.getBlockEntity(pos);
            if (be == null) return 0L;
            var injStack = DEReflection.getInjectorStack(be);
            if (injStack == null || !injStack.isEmpty()) return 0L;
            if (mode == Actionable.MODULATE) {
                DEReflection.setInjectorStack(be, unit.stack());
            }
            return 1L;
        };
    }

    // ===== Helpers =====

    @Nullable
    private static List<PlannedUnit> expandInputUnits(KeyCounter[] inputs) {
        var units = new ArrayList<PlannedUnit>();
        long total = 0;
        for (var counter : inputs) {
            for (var entry : counter) {
                if (!(entry.getKey() instanceof AEItemKey itemKey)) return null;
                long amount = entry.getLongValue();
                if (amount <= 0) continue;
                total += amount;
                if (total > MAX_INPUT_UNITS) return null;
                for (long i = 0; i < amount; i++) {
                    units.add(new PlannedUnit(itemKey, 1));
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

    private static boolean isDeLoaded() {
        return ModList.get().isLoaded(MOD_ID);
    }

    private static ResourceLocation blockId(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock());
    }

    private static ResourceLocation deId(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }

    // ===== Records =====

    private record PlannedUnit(AEItemKey key, int amount) {
        PlannedUnit {
            Objects.requireNonNull(key, "key");
        }

        ItemStack stack() {
            return key.toStack(amount);
        }

        GenericStack toGenericStack() {
            return new GenericStack(key, amount);
        }
    }

    private record InjectorInfo(BlockPos pos, int tierIndex, boolean empty) {}

    private record InjectorAssignment(BlockPos injectorPos, PlannedUnit unit) {}

    private record IngredientInfo(Ingredient ingredient, boolean consume) {}

    private record RecipeMatch(PlannedUnit catalyst, List<InjectorAssignment> injectorAssignments, ItemStack result) {}

    /** Opaque binding handle returned from {@link #bind}. */
    private record FusionBindHandle(Object recipe) {}

    // ===== DEReflection =====

    private static final class DEReflection {
        private static final String CORE_CLASS =
                "com.brandon3055.draconicevolution.blocks.tileentity.TileFusionCraftingCore";
        private static final String INJECTOR_CLASS =
                "com.brandon3055.draconicevolution.blocks.tileentity.TileFusionCraftingInjector";
        private static final String FUSION_RECIPE_CLASS =
                "com.brandon3055.draconicevolution.api.crafting.IFusionRecipe";
        private static final String FUSION_INGREDIENT_CLASS =
                "com.brandon3055.draconicevolution.api.crafting.IFusionRecipe$IFusionIngredient";
        private static final String STACK_INGREDIENT_CLASS =
                "com.brandon3055.draconicevolution.api.crafting.StackIngredient";
        private static final String DRACONIC_API_CLASS =
                "com.brandon3055.draconicevolution.api.DraconicAPI";
        private static final String TECH_LEVEL_CLASS =
                "com.brandon3055.brandonscore.api.TechLevel";

        private static volatile boolean lookupDone;
        private static volatile @Nullable Method isCraftingMethod;
        private static volatile @Nullable Method startCraftMethod;
        private static volatile @Nullable Method getInjectorsMethod;
        private static volatile @Nullable Method getInjectorStackMethod;
        private static volatile @Nullable Method setInjectorStackMethod;
        private static volatile @Nullable Method getInjectorTierMethod;
        private static volatile @Nullable Method getCatalystStackMethod;
        private static volatile @Nullable Method setCatalystStackMethod;
        private static volatile @Nullable Method getOutputStackMethod;
        private static volatile @Nullable Method setOutputStackMethod;
        private static volatile @Nullable Method fusionIngredientsMethod;
        private static volatile @Nullable Method getCatalystMethod;
        private static volatile @Nullable Method getRecipeTierMethod;
        private static volatile @Nullable Method getResultItemMethod;
        private static volatile @Nullable Method ingredientGetMethod;
        private static volatile @Nullable Method ingredientConsumeMethod;
        private static volatile @Nullable Field techLevelIndexField;
        private static volatile @Nullable Method getCustomIngredientMethod;
        private static volatile @Nullable Method stackIngredientCountMethod;
        private static volatile @Nullable Object fusionRecipeType;

        static boolean isCrafting(BlockEntity be) {
            ensureLookup();
            if (isCraftingMethod == null) return true;
            try {
                return Boolean.TRUE.equals(isCraftingMethod.invoke(be));
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                return true;
            }
        }

        static void startCraft(BlockEntity be) {
            ensureLookup();
            if (startCraftMethod == null) return;
            try {
                startCraftMethod.invoke(be);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {}
        }

        static void startCraftIfIdle(BlockEntity be) {
            if (!isCrafting(be)) {
                startCraft(be);
            }
        }

        @Nullable
        static ItemStack getCatalystStack(BlockEntity be) {
            ensureLookup();
            if (getCatalystStackMethod == null) return null;
            try {
                var result = getCatalystStackMethod.invoke(be);
                return result instanceof ItemStack s ? s : null;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                return null;
            }
        }

        static void setCatalystStack(BlockEntity be, ItemStack stack) {
            ensureLookup();
            if (setCatalystStackMethod == null) return;
            try {
                setCatalystStackMethod.invoke(be, stack);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {}
        }

        @Nullable
        static ItemStack getOutputStack(BlockEntity be) {
            ensureLookup();
            if (getOutputStackMethod == null) return null;
            try {
                var result = getOutputStackMethod.invoke(be);
                return result instanceof ItemStack s ? s : null;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                return null;
            }
        }

        static ItemStack extractOutput(BlockEntity be) {
            ensureLookup();
            if (getOutputStackMethod == null || setOutputStackMethod == null) return ItemStack.EMPTY;
            try {
                var result = getOutputStackMethod.invoke(be);
                if (!(result instanceof ItemStack stack) || stack.isEmpty()) return ItemStack.EMPTY;
                var copy = stack.copy();
                setOutputStackMethod.invoke(be, ItemStack.EMPTY);
                return copy;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                return ItemStack.EMPTY;
            }
        }


        @Nullable
        static ItemStack getInjectorStack(BlockEntity be) {
            ensureLookup();
            if (getInjectorStackMethod == null) return null;
            try {
                var result = getInjectorStackMethod.invoke(be);
                return result instanceof ItemStack s ? s : null;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                return null;
            }
        }

        static void setInjectorStack(BlockEntity be, ItemStack stack) {
            ensureLookup();
            if (setInjectorStackMethod == null) return;
            try {
                setInjectorStackMethod.invoke(be, stack);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {}
        }

        @Nullable
        @SuppressWarnings("unchecked")
        static List<InjectorInfo> getInjectors(BlockEntity core, ServerLevel level) {
            ensureLookup();
            if (getInjectorsMethod == null) return null;
            try {
                var raw = getInjectorsMethod.invoke(core);
                if (!(raw instanceof List<?> list)) return null;
                var result = new ArrayList<InjectorInfo>(list.size());
                for (var obj : list) {
                    if (obj instanceof BlockEntity injBe) {
                        var stack = getInjectorStack(injBe);
                        int tier = getInjectorTierIndex(injBe);
                        if (stack != null && tier >= 0) {
                            result.add(new InjectorInfo(injBe.getBlockPos(), tier, stack.isEmpty()));
                        }
                    }
                }
                return result;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                return null;
            }
        }

        private static int getInjectorTierIndex(BlockEntity injBe) {
            ensureLookup();
            if (getInjectorTierMethod == null || techLevelIndexField == null) return -1;
            try {
                var tier = getInjectorTierMethod.invoke(injBe);
                if (tier == null) return -1;
                return techLevelIndexField.getInt(tier);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                return -1;
            }
        }


        @Nullable
        @SuppressWarnings("unchecked")
        static List<RecipeHolder<?>> getFusionRecipes(ServerLevel level) {
            ensureLookup();
            if (fusionRecipeType == null) return null;
            try {
                var allRecipes = level.getRecipeManager()
                        .getAllRecipesFor((RecipeType) fusionRecipeType);
                return (List<RecipeHolder<?>>) (List<?>) allRecipes;
            } catch (RuntimeException | LinkageError e) {
                return null;
            }
        }

        @Nullable
        static Ingredient getCatalyst(Object recipe) {
            ensureLookup();
            if (getCatalystMethod == null) return null;
            try {
                var result = getCatalystMethod.invoke(recipe);
                return result instanceof Ingredient ing ? ing : null;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                return null;
            }
        }

        @Nullable
        static List<IngredientInfo> getFusionIngredients(Object recipe) {
            ensureLookup();
            if (fusionIngredientsMethod == null || ingredientGetMethod == null
                    || ingredientConsumeMethod == null) return null;
            try {
                var raw = fusionIngredientsMethod.invoke(recipe);
                if (!(raw instanceof List<?> list)) return null;
                var result = new ArrayList<IngredientInfo>(list.size());
                for (var obj : list) {
                    var ing = ingredientGetMethod.invoke(obj);
                    var consume = ingredientConsumeMethod.invoke(obj);
                    if (ing instanceof Ingredient ingredient && consume instanceof Boolean b) {
                        result.add(new IngredientInfo(ingredient, b));
                    }
                }
                return result;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                return null;
            }
        }

        static int getRecipeTierIndex(Object recipe) {
            ensureLookup();
            if (getRecipeTierMethod == null || techLevelIndexField == null) return -1;
            try {
                var tier = getRecipeTierMethod.invoke(recipe);
                if (tier == null) return -1;
                return techLevelIndexField.getInt(tier);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                return -1;
            }
        }


        @Nullable
        static ItemStack getResultItem(Object recipe, ServerLevel level) {
            ensureLookup();
            if (getResultItemMethod == null) return null;
            try {
                var result = getResultItemMethod.invoke(recipe, level.registryAccess());
                return result instanceof ItemStack s ? s.copy() : null;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                return null;
            }
        }

        static int getCatalystCount(Object recipe) {
            ensureLookup();
            if (getCatalystMethod == null) return 1;
            try {
                var catalyst = getCatalystMethod.invoke(recipe);
                if (!(catalyst instanceof Ingredient ing)) return 1;
                if (getCustomIngredientMethod == null) return 1;
                var custom = getCustomIngredientMethod.invoke(ing);
                if (custom == null) return 1;
                var stackIngClass = ReflectionSupport.findClassCached(STACK_INGREDIENT_CLASS).orElse(null);
                if (stackIngClass == null) return 1;
                if (!stackIngClass.isInstance(custom)) return 1;
                if (stackIngredientCountMethod == null) {
                    stackIngredientCountMethod = ReflectionSupport.findMethodCached(stackIngClass, "getCount")
                            .orElse(null);
                }
                if (stackIngredientCountMethod == null) {
                    return 1;
                }
                var count = stackIngredientCountMethod.invoke(custom);
                return count instanceof Number n ? n.intValue() : 1;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                return 1;
            }
        }

        private static void ensureLookup() {
            if (lookupDone) return;
            synchronized (DEReflection.class) {
                if (lookupDone) return;
                try {
                    doLookup();
                } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                } finally {
                    lookupDone = true;
                }
            }
        }

        @SuppressWarnings("unchecked")
        private static void doLookup() throws ReflectiveOperationException {
            var coreClass = Class.forName(CORE_CLASS);
            isCraftingMethod = coreClass.getMethod("isCrafting");
            startCraftMethod = coreClass.getMethod("startCraft");
            getCatalystStackMethod = coreClass.getMethod("getCatalystStack");
            setCatalystStackMethod = coreClass.getMethod("setCatalystStack", ItemStack.class);
            getOutputStackMethod = coreClass.getMethod("getOutputStack");
            setOutputStackMethod = coreClass.getMethod("setOutputStack", ItemStack.class);
            getInjectorsMethod = coreClass.getMethod("getInjectors");

            var injectorClass = Class.forName(INJECTOR_CLASS);
            getInjectorStackMethod = injectorClass.getMethod("getInjectorStack");
            setInjectorStackMethod = injectorClass.getMethod("setInjectorStack", ItemStack.class);
            getInjectorTierMethod = injectorClass.getMethod("getInjectorTier");

            var techLevelClass = Class.forName(TECH_LEVEL_CLASS);
            techLevelIndexField = techLevelClass.getField("index");

            var recipeClass = Class.forName(FUSION_RECIPE_CLASS);
            fusionIngredientsMethod = recipeClass.getMethod("fusionIngredients");
            getCatalystMethod = recipeClass.getMethod("getCatalyst");
            getRecipeTierMethod = recipeClass.getMethod("getRecipeTier");
            getResultItemMethod = recipeClass.getMethod("getResultItem",
                    net.minecraft.core.HolderLookup.Provider.class);

            var ingredientClass = Class.forName(FUSION_INGREDIENT_CLASS);
            ingredientGetMethod = ingredientClass.getMethod("get");
            ingredientConsumeMethod = ingredientClass.getMethod("consume");

            getCustomIngredientMethod = Ingredient.class.getMethod("getCustomIngredient");

            // Resolve fusion recipe type from DraconicAPI
            var apiClass = Class.forName(DRACONIC_API_CLASS);
            var typeField = apiClass.getField("FUSION_RECIPE_TYPE");
            var holder = typeField.get(null);
            if (holder != null) {
                var getMethod = holder.getClass().getMethod("get");
                fusionRecipeType = getMethod.invoke(holder);
            }
        }
    }
}
