package com.moakiee.ae2lt.packaged.logic.multiblock.ars;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.fml.ModList;

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
import com.moakiee.ae2lt.packaged.logic.multiblock.ReflectionSupport;
import com.moakiee.ae2lt.packaged.logic.multiblock.TargetSlot;
import com.moakiee.ae2lt.packaged.logic.multiblock.binding.BindingMode;
import com.moakiee.ae2lt.packaged.logic.multiblock.binding.BindingResult;
import com.moakiee.thunderbolt.ae2.overload.model.MatchMode;
import com.moakiee.thunderbolt.ae2.overload.pattern.OverloadedProviderOnlyPatternDetails;

/**
 * Runtime adapter for Ars Nouveau's Enchanting Apparatus.
 *
 * This intentionally stays reflection-based: Ars Nouveau is an optional
 * runtime integration, not a compile dependency of AE2LT.
 */
public final class ArsNouveauEnchantingApparatusAdapter implements MultiblockAdapter {

    private static final String MOD_ID = "ars_nouveau";
    private static final ResourceLocation APPARATUS_BLOCK = arsId("enchanting_apparatus");
    private static final ResourceLocation PEDESTAL_BLOCK = arsId("arcane_pedestal");
    private static final ResourceLocation ARCANE_PLATFORM_BLOCK = arsId("arcane_platform");
    private static final ResourceLocation ARCANE_CORE_BLOCK = arsId("arcane_core");
    private static final int PEDESTAL_RADIUS = 3;
    private static final int SOURCE_RADIUS = 10;
    private static final int MAX_INPUT_UNITS = 128;

    private static final List<ResourceLocation> FALLBACK_RECIPE_TYPES = List.of(
            arsId("enchanting_apparatus"),
            arsId("enchantment"),
            arsId("reactive_enchantment"),
            arsId("spell_write"),
            arsId("armor_upgrade"),
            arsId("prestidigitation"));

    @Override
    public int priority() {
        return 100;
    }

    @Override
    public boolean recognizesMain(ServerLevel level, BlockPos pos, BlockEntity be) {
        return be != null
                && isArsLoaded()
                && blockId(be.getBlockState()).equals(APPARATUS_BLOCK)
                && be instanceof Container;
    }

    @Override
    public ResourceLocation requiredAdapterId(ServerLevel level, BlockPos pos) {
        return com.moakiee.ae2lt.packaged.item.AdapterIds.ARS_APPARATUS;
    }

    @Override
    @Nullable
    public BindingResult bind(ServerLevel level, BlockPos mainPos, IPatternDetails pattern) {
        var be = level.getBlockEntity(mainPos);
        if (be == null || !recognizesMain(level, mainPos, be) || !(be instanceof Container)) {
            return null;
        }
        if (!hasSingleItemOutput(pattern)) {
            return null;
        }
        var found = findCandidateRecipes(level, pattern);
        if (found.candidates().isEmpty()) {
            return null;
        }
        return new BindingResult(found, BindingMode.REAL);
    }

    @Override
    public boolean canDispatch(ServerLevel level, BlockPos mainPos, Object handle) {
        if (!(handle instanceof ApparatusBindHandle bind)) return false;
        var be = level.getBlockEntity(mainPos);
        if (be == null || !recognizesMain(level, mainPos, be) || !(be instanceof Container apparatus)) {
            return false;
        }
        if (isCrafting(be) || !containerEmpty(apparatus) || !hasValidArcaneCore(level, mainPos)) {
            return false;
        }
        return bind.candidates().stream()
                .anyMatch(candidate -> hasSourceAvailable(level, mainPos, candidate.sourceCost()));
    }

    @Override
    @Nullable
    public DispatchPlan planWithBinding(ServerLevel level, BlockPos mainPos,
                                        IPatternDetails pattern, KeyCounter[] inputs,
                                        Object handle, IActionSource source) {
        if (!(handle instanceof ApparatusBindHandle bind)) return null;

        var pedestals = findEmptyPedestals(level, mainPos);
        if (pedestals == null) {
            return null;
        }

        var units = expandInputUnits(inputs);
        if (units == null || units.isEmpty() || units.size() - 1 > pedestals.size()) {
            return null;
        }

        RecipeMatch match = null;
        for (var candidate : bind.candidates()) {
            var candidateMatch = assignInputsToRecipe(level, candidate.recipe(), units, candidate.sourceCost());
            if (candidateMatch == null) {
                continue;
            }
            if (!hasSourceAvailable(level, mainPos, candidate.sourceCost())) {
                continue;
            }
            match = candidateMatch;
            break;
        }
        if (match == null) {
            return null;
        }

        var targets = new ArrayList<TargetSlot>(match.pedestalUnits().size() + 1);
        for (int i = 0; i < match.pedestalUnits().size(); i++) {
            var unit = match.pedestalUnits().get(i);
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
                apparatusInserter(level, mainPos, match.reagent(), match.sourceCost())));

        return new DispatchPlan(List.copyOf(targets), null);
    }

    @Override
    public List<GenericStack> extractOutputs(ServerLevel level, BlockPos mainPos,
                                             AllowedOutputFilter filter,
                                             IActionSource source) {
        var be = level.getBlockEntity(mainPos);
        if (be == null || !recognizesMain(level, mainPos, be) || !(be instanceof Container apparatus)) {
            return List.of();
        }
        if (isCrafting(be) || apparatus.getContainerSize() <= 0) {
            return List.of();
        }

        var stack = apparatus.getItem(0);
        if (stack.isEmpty()) {
            return List.of();
        }

        var key = AEItemKey.of(stack);
        if (!allowsAutoReturn(level, mainPos, filter, key)) {
            return List.of();
        }

        var extracted = apparatus.removeItemNoUpdate(0);
        if (extracted.isEmpty()) {
            return List.of();
        }

        return List.of(new GenericStack(AEItemKey.of(extracted), extracted.getCount()));
    }

    /**
     * Bind-time recipe search. Keeps every {@code (recipe, sourceCost)} tuple
     * whose result matches the pattern output. Input matching has to wait until
     * push time because AE supplies the selected alternative inputs there.
     */
    private ApparatusBindHandle findCandidateRecipes(ServerLevel level, IPatternDetails pattern) {
        var candidates = new ArrayList<RecipeCandidate>();
        for (var holder : recipes(level)) {
            var recipe = holder.value();
            var result = resultItem(recipe, level);
            if (result.isEmpty() || !outputMatches(pattern, result)) {
                continue;
            }
            int sourceCost = sourceCost(recipe);
            if (sourceCost < 0) {
                continue;
            }
            candidates.add(new RecipeCandidate(recipe, sourceCost));
        }
        return new ApparatusBindHandle(List.copyOf(candidates));
    }

    /**
     * Per-push input assignment: tries each input as the reagent, then validates
     * the remaining units as pedestals via Ars's own {@code matches} hook.
     */
    @Nullable
    private RecipeMatch assignInputsToRecipe(ServerLevel level, Object recipe,
                                              List<PlannedUnit> units, int sourceCost) {
        for (int reagentIndex = 0; reagentIndex < units.size(); reagentIndex++) {
            var reagent = units.get(reagentIndex);
            var pedestalUnits = new ArrayList<PlannedUnit>(units.size() - 1);
            var pedestalStacks = new ArrayList<ItemStack>(units.size() - 1);
            for (int i = 0; i < units.size(); i++) {
                if (i == reagentIndex) {
                    continue;
                }
                var unit = units.get(i);
                pedestalUnits.add(unit);
                pedestalStacks.add(unit.stack());
            }

            if (recipe instanceof Recipe<?> typedRecipe) {
                var input = ArsReflection.createApparatusInput(reagent.stack(), pedestalStacks);
                if (input == null || !recipeMatches(typedRecipe, input, level)) {
                    continue;
                }
            } else {
                continue;
            }
            return new RecipeMatch(reagent, List.copyOf(pedestalUnits), sourceCost);
        }
        return null;
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

    private static BiFunction<GenericStack, Actionable, Long> apparatusInserter(
            ServerLevel level, BlockPos pos, PlannedUnit unit, int sourceCost) {
        return (stack, mode) -> {
            if (!matchesPlannedStack(stack, unit)) {
                return 0L;
            }
            var be = level.getBlockEntity(pos);
            if (!(be instanceof Container apparatus)
                    || !blockId(be.getBlockState()).equals(APPARATUS_BLOCK)
                    || isCrafting(be)
                    || !containerEmpty(apparatus)
                    || !hasValidArcaneCore(level, pos)
                    || !hasSourceAvailable(level, pos, sourceCost)) {
                return 0L;
            }

            if (mode == Actionable.MODULATE) {
                apparatus.setItem(0, unit.stack());
                apparatus.setChanged();
                return isCrafting(be) ? 1L : 0L;
            }
            return 1L;
        };
    }

    @Nullable
    private static Container pedestalContainer(ServerLevel level, BlockPos pos) {
        if (!level.isLoaded(pos)) {
            return null;
        }
        var be = level.getBlockEntity(pos);
        if (be instanceof Container container && isPedestalBlock(be.getBlockState())) {
            return container;
        }
        return null;
    }

    @Nullable
    private static List<BlockPos> findEmptyPedestals(ServerLevel level, BlockPos mainPos) {
        var positions = new ArrayList<BlockPos>();
        var min = mainPos.offset(-PEDESTAL_RADIUS, -PEDESTAL_RADIUS, -PEDESTAL_RADIUS);
        var max = mainPos.offset(PEDESTAL_RADIUS, PEDESTAL_RADIUS, PEDESTAL_RADIUS);

        for (var mutablePos : BlockPos.betweenClosed(min, max)) {
            var pos = mutablePos.immutable();
            // Ars itself simply finds block entities in this cube. Near the
            // world's build limits the cube extends outside valid Y values;
            // ServerLevel#isLoaded reports false there even though no chunk is
            // actually missing, so those positions must not reject dispatch.
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
            positions.add(pos);
        }
        return positions;
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
        return units;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static boolean recipeMatches(Recipe<?> recipe, RecipeInput input, ServerLevel level) {
        try {
            return rawRecipe(recipe).matches(input, level);
        } catch (RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Recipe rawRecipe(Recipe<?> recipe) {
        return (Recipe) recipe;
    }

    private static int sourceCost(Object recipe) {
        try {
            Method method = ReflectionSupport.findMethodCached(recipe.getClass(), "sourceCost").orElse(null);
            if (method == null) {
                return -1;
            }
            Object value = method.invoke(recipe);
            return value instanceof Number number ? number.intValue() : -1;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return -1;
        }
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

    private static boolean hasSingleItemOutput(IPatternDetails pattern) {
        var outputs = pattern.getOutputs();
        return outputs.size() == 1 && outputs.getFirst().what() instanceof AEItemKey;
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

    private static boolean hasValidArcaneCore(ServerLevel level, BlockPos mainPos) {
        var apparatusState = level.getBlockState(mainPos);
        if (!apparatusState.hasProperty(BlockStateProperties.FACING)) {
            return false;
        }

        var apparatusFacing = apparatusState.getValue(BlockStateProperties.FACING);
        var corePos = mainPos.relative(apparatusFacing.getOpposite());
        if (!level.isLoaded(corePos)) {
            return false;
        }

        var coreState = level.getBlockState(corePos);
        if (!blockId(coreState).equals(ARCANE_CORE_BLOCK)
                || !coreState.hasProperty(BlockStateProperties.FACING)) {
            return false;
        }

        return coreState.getValue(BlockStateProperties.FACING).getAxis() == apparatusFacing.getAxis();
    }

    private static boolean isCrafting(BlockEntity be) {
        try {
            var field = ReflectionSupport.findFieldCached(be.getClass(), "isCrafting").orElse(null);
            return field != null && field.getBoolean(be);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return true;
        }
    }

    private static boolean hasSourceAvailable(ServerLevel level, BlockPos mainPos, int sourceCost) {
        return sourceCost <= 0 || ArsReflection.hasSourceNearby(mainPos, level, SOURCE_RADIUS, sourceCost);
    }

    private static List<RecipeHolder<?>> recipes(ServerLevel level) {
        var apiRecipes = ArsReflection.getEnchantingApparatusRecipes(level);
        if (!apiRecipes.isEmpty()) {
            return apiRecipes;
        }

        var recipes = new ArrayList<RecipeHolder<?>>();
        for (var id : FALLBACK_RECIPE_TYPES) {
            BuiltInRegistries.RECIPE_TYPE.getOptional(id)
                    .ifPresent(type -> recipes.addAll(recipesForType(level, type)));
        }
        return recipes;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static List<RecipeHolder<?>> recipesForType(ServerLevel level, RecipeType<?> type) {
        return (List<RecipeHolder<?>>) (List<?>) level.getRecipeManager().getAllRecipesFor((RecipeType) type);
    }

    private static boolean isArsLoaded() {
        return ModList.get().isLoaded(MOD_ID);
    }

    private static ResourceLocation blockId(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock());
    }

    private static boolean isPedestalBlock(BlockState state) {
        var id = blockId(state);
        return id.equals(PEDESTAL_BLOCK) || id.equals(ARCANE_PLATFORM_BLOCK);
    }

    private static ResourceLocation arsId(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
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

    private record RecipeMatch(PlannedUnit reagent, List<PlannedUnit> pedestalUnits, int sourceCost) {
    }

    /** Opaque binding handle returned from {@link #bind}. */
    private record RecipeCandidate(Object recipe, int sourceCost) {
    }

    private record ApparatusBindHandle(List<RecipeCandidate> candidates) {
        ApparatusBindHandle {
            candidates = List.copyOf(candidates);
        }
    }

    private static final class ArsReflection {
        private static final String APPARATUS_INPUT_CLASS =
                "com.hollingsworth.arsnouveau.common.crafting.recipes.ApparatusRecipeInput";
        private static final String API_CLASS = "com.hollingsworth.arsnouveau.api.ArsNouveauAPI";
        private static final String SOURCE_UTIL_CLASS = "com.hollingsworth.arsnouveau.api.util.SourceUtil";

        private static volatile @Nullable Constructor<?> apparatusInputConstructor;
        private static volatile boolean apparatusInputLookupDone;
        private static volatile @Nullable Method getInstanceMethod;
        private static volatile @Nullable Method getRecipesMethod;
        private static volatile boolean apiLookupDone;
        private static volatile @Nullable Method hasSourceNearbyMethod;
        private static volatile boolean sourceLookupDone;

        @Nullable
        static RecipeInput createApparatusInput(ItemStack reagent, List<ItemStack> pedestals) {
            var constructor = apparatusInputConstructor();
            if (constructor == null) {
                return null;
            }
            try {
                Object input = constructor.newInstance(reagent.copy(), copyStacks(pedestals), null);
                return input instanceof RecipeInput recipeInput ? recipeInput : null;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                return null;
            }
        }

        static List<RecipeHolder<?>> getEnchantingApparatusRecipes(ServerLevel level) {
            if (!isArsLoaded()) {
                return List.of();
            }
            try {
                var getInstance = getInstanceMethod();
                var getRecipes = getRecipesMethod();
                if (getInstance == null || getRecipes == null) {
                    return List.of();
                }
                Object api = getInstance.invoke(null);
                Object value = getRecipes.invoke(api, level);
                if (!(value instanceof Iterable<?> iterable)) {
                    return List.of();
                }

                var recipes = new ArrayList<RecipeHolder<?>>();
                for (var item : iterable) {
                    if (item instanceof RecipeHolder<?> holder && holder.value() instanceof Recipe<?>) {
                        recipes.add(holder);
                    }
                }
                return recipes;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                return List.of();
            }
        }

        static boolean hasSourceNearby(BlockPos pos, Level level, int radius, int cost) {
            if (!isArsLoaded()) {
                return false;
            }
            try {
                var method = hasSourceNearbyMethod();
                return method != null && Boolean.TRUE.equals(method.invoke(null, pos, level, radius, cost));
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                return false;
            }
        }

        @Nullable
        private static Constructor<?> apparatusInputConstructor() {
            if (apparatusInputLookupDone) {
                return apparatusInputConstructor;
            }
            synchronized (ArsReflection.class) {
                if (apparatusInputLookupDone) {
                    return apparatusInputConstructor;
                }
                try {
                    var clazz = Class.forName(APPARATUS_INPUT_CLASS);
                    apparatusInputConstructor = clazz.getConstructor(ItemStack.class, List.class, Player.class);
                } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                    apparatusInputConstructor = null;
                } finally {
                    apparatusInputLookupDone = true;
                }
                return apparatusInputConstructor;
            }
        }

        @Nullable
        private static Method getInstanceMethod() {
            ensureApiMethods();
            return getInstanceMethod;
        }

        @Nullable
        private static Method getRecipesMethod() {
            ensureApiMethods();
            return getRecipesMethod;
        }

        private static void ensureApiMethods() {
            if (apiLookupDone) {
                return;
            }
            synchronized (ArsReflection.class) {
                if (apiLookupDone) {
                    return;
                }
                try {
                    var clazz = Class.forName(API_CLASS);
                    getInstanceMethod = clazz.getMethod("getInstance");
                    getRecipesMethod = clazz.getMethod("getEnchantingApparatusRecipes", Level.class);
                } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                    getInstanceMethod = null;
                    getRecipesMethod = null;
                } finally {
                    apiLookupDone = true;
                }
            }
        }

        @Nullable
        private static Method hasSourceNearbyMethod() {
            if (sourceLookupDone) {
                return hasSourceNearbyMethod;
            }
            synchronized (ArsReflection.class) {
                if (sourceLookupDone) {
                    return hasSourceNearbyMethod;
                }
                try {
                    var clazz = Class.forName(SOURCE_UTIL_CLASS);
                    hasSourceNearbyMethod = clazz.getMethod(
                            "hasSourceNearby", BlockPos.class, Level.class, int.class, int.class);
                } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                    hasSourceNearbyMethod = null;
                } finally {
                    sourceLookupDone = true;
                }
                return hasSourceNearbyMethod;
            }
        }

        private static List<ItemStack> copyStacks(List<ItemStack> stacks) {
            return stacks.stream().map(ItemStack::copy).toList();
        }
    }
}
