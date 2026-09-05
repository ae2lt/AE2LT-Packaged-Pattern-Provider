package com.moakiee.ae2lt.packaged.logic.multiblock.ars;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.fml.ModList;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;

import com.moakiee.ae2lt.packaged.patternprovider.AllowedOutputFilter;
import com.moakiee.ae2lt.packaged.logic.multiblock.AdapterRecipeTypes;
import com.moakiee.ae2lt.packaged.logic.multiblock.DispatchPlan;
import com.moakiee.ae2lt.packaged.logic.multiblock.AdapterBlocks;
import com.moakiee.ae2lt.packaged.logic.multiblock.InsertionStrategy;
import com.moakiee.ae2lt.packaged.logic.multiblock.MultiblockAdapter;
import com.moakiee.ae2lt.packaged.logic.multiblock.ReflectionSupport;
import com.moakiee.ae2lt.packaged.logic.multiblock.TargetSlot;
import com.moakiee.ae2lt.packaged.logic.multiblock.binding.BindingMode;
import com.moakiee.ae2lt.packaged.logic.multiblock.binding.BindingResult;
import com.moakiee.ae2lt.packaged.patternprovider.OverloadPatternSemantics;

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
        if (isCrafting(be) || !containerEmpty(apparatus)) {
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
        for (var recipe : recipes(level)) {
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
                if (!ArsReflection.matchesApparatusRecipe(rawRecipe(typedRecipe), pedestalStacks, reagent.stack())) {
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
        var result = ArsReflection.recipeResult(recipe);
        if (result != null && !result.isEmpty()) {
            return result;
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
    private static Recipe rawRecipe(Recipe<?> recipe) {
        return (Recipe) recipe;
    }

    private static int sourceCost(Object recipe) {
        // 1.20.x declares a {@code getSourceCost()} getter; other versions used
        // a plain {@code sourceCost()} method.
        for (String name : new String[] {"getSourceCost", "sourceCost"}) {
            Method method = ReflectionSupport.findMethodCached(recipe.getClass(), name).orElse(null);
            if (method == null) {
                continue;
            }
            try {
                Object value = method.invoke(recipe);
                if (value instanceof Number number) {
                    return number.intValue();
                }
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                return -1;
            }
        }
        return -1;
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

    private static List<Recipe<?>> recipes(ServerLevel level) {
        var apiRecipes = ArsReflection.getEnchantingApparatusRecipes(level);
        if (!apiRecipes.isEmpty()) {
            return apiRecipes;
        }

        var recipes = new ArrayList<Recipe<?>>();
        for (var id : FALLBACK_RECIPE_TYPES) {
            var type = AdapterRecipeTypes.find(id);
            if (type != null) {
                recipes.addAll(recipesForType(level, type));
            }
        }
        return recipes;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static List<Recipe<?>> recipesForType(ServerLevel level, RecipeType<?> type) {
        return (List<Recipe<?>>) (List<?>) level.getRecipeManager().getAllRecipesFor((RecipeType) type);
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
        private static final String API_CLASS = "com.hollingsworth.arsnouveau.api.ArsNouveauAPI";
        private static final String SOURCE_UTIL_CLASS = "com.hollingsworth.arsnouveau.api.util.SourceUtil";

        private static final Map<Class<?>, Map<String, Method>> INSTANCE_METHODS = new HashMap<>();

        private static volatile @Nullable Method getInstanceMethod;
        private static volatile @Nullable Method getRecipesMethod;
        private static volatile boolean apiLookupDone;
        private static volatile @Nullable Method hasSourceNearbyMethod;
        private static volatile boolean sourceLookupDone;

        /**
         * Result of an {@code IEnchantingRecipe}. On 1.20.x the apparatus
         * recipes return {@link Recipe#getResultItem(RegistryAccess)}
         * unconditionally empty, so the interface's own
         * {@code getResult(pedestalItems, reagent, tile)} hook must be used.
         */
        @Nullable
        static ItemStack recipeResult(Object recipe) {
            var method = instanceMethod(recipe.getClass(), "getResult");
            if (method == null) {
                return null;
            }
            try {
                Object out = method.invoke(recipe, List.of(), ItemStack.EMPTY, null);
                return out instanceof ItemStack stack ? stack.copy() : null;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                return null;
            }
        }

        /** Apparatus match against explicit pedestal stacks plus the reagent stack. */
        static boolean matchesApparatusRecipe(Object recipe, List<ItemStack> pedestals, ItemStack reagent) {
            var method = instanceMethod(recipe.getClass(), "isMatch");
            if (method == null) {
                return false;
            }
            try {
                Object out = method.invoke(recipe, copyStacks(pedestals), reagent.copy(), null, null);
                return Boolean.TRUE.equals(out);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                return false;
            }
        }

        /**
         * Finds a zero-cost or low-shape hook by name: argument types moved
         * between mod versions ({@code getSourceCost}/{@code sourceCost},
         * varying trailing tile/player parameters), so resolution matches on
         * declared parameter kinds instead of exact signatures.
         */
        @Nullable
        private static synchronized Method instanceMethod(Class<?> recipeClass, String name) {
            var perClass = INSTANCE_METHODS.get(recipeClass);
            if (perClass != null && perClass.containsKey(name)) {
                return perClass.get(name);
            }
            Method found = null;
            for (var candidate : recipeClass.getMethods()) {
                if (!candidate.getName().equals(name)) {
                    continue;
                }
                var params = candidate.getParameterTypes();
                if (name.equals("isMatch") && params.length == 4
                        && List.class.isAssignableFrom(params[0])
                        && ItemStack.class.isAssignableFrom(params[1])) {
                    found = candidate;
                    break;
                }
                if (name.equals("getResult") && params.length == 3
                        && List.class.isAssignableFrom(params[0])
                        && ItemStack.class.isAssignableFrom(params[1])) {
                    found = candidate;
                    break;
                }
            }
            INSTANCE_METHODS.computeIfAbsent(recipeClass, k -> new HashMap<>()).put(name, found);
            return found;
        }

        static List<Recipe<?>> getEnchantingApparatusRecipes(ServerLevel level) {
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

                var recipes = new ArrayList<Recipe<?>>();
                for (var item : iterable) {
                    if (item instanceof Recipe<?> holder) {
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
