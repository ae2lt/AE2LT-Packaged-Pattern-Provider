package com.moakiee.ae2lt.packaged.logic.multiblock.occultism;

import java.lang.reflect.Field;
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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.items.IItemHandler;

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
/**
 * Runtime adapter for Occultism rituals.
 *
 * <p>Occultism is intentionally optional, so all Occultism and Modonomicon access
 * stays reflection-based.
 *
 * <p>All Occultism rituals run as {@link BindingMode#REAL}. Even summon/use-item
 * rituals have visible time and world-side side effects; this adapter only
 * automates item placement plus optional proxy costs for sacrifice/use steps.
 */
public final class OccultismRitualAdapter implements MultiblockAdapter {

    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger("ae2ltpp/occultism-ritual");

    private static final String MOD_ID = "occultism";
    private static final ResourceLocation RITUAL_RECIPE_TYPE = occultismId("ritual");
    private static final int MAX_INPUT_UNITS = 128;

    @Override
    public int priority() {
        return 100;
    }

    @Override
    public boolean recognizesMain(ServerLevel level, BlockPos pos, BlockEntity be) {
        return be != null && isOccultismLoaded() && OccultismReflection.isGoldenBowl(be);
    }

    @Override
    public ResourceLocation requiredAdapterId(ServerLevel level, BlockPos pos) {
        return com.moakiee.ae2lt.packaged.item.AdapterIds.OCCULTISM_RITUAL;
    }

    @Override
    @Nullable
    public BindingResult bind(ServerLevel level, BlockPos mainPos, IPatternDetails pattern) {
        var be = level.getBlockEntity(mainPos);
        if (be == null || !recognizesMain(level, mainPos, be)) {
            LOG.debug("bind: skipped at {} (be={}, recognized={})",
                    mainPos, be == null ? "null" : be.getClass().getSimpleName(),
                    be != null && recognizesMain(level, mainPos, be));
            return null;
        }
        var candidate = findCandidateRecipe(level, mainPos, pattern);
        if (candidate == null) {
            LOG.debug("bind: no ritual recipe matched pattern inputs at {}", mainPos);
            return null;
        }
        LOG.debug("bind: matched ritual at {} -> {}", mainPos, candidate.recipe().getClass().getSimpleName());
        return new BindingResult(candidate, BindingMode.REAL);
    }

    @Override
    public boolean canDispatch(ServerLevel level, BlockPos mainPos, Object handle) {
        if (!(handle instanceof OccultismBindHandle bind)) {
            LOG.debug("canDispatch: handle wrong type at {}", mainPos);
            return false;
        }
        var be = level.getBlockEntity(mainPos);
        if (be == null || !recognizesMain(level, mainPos, be)) {
            LOG.debug("canDispatch: not recognized at {}", mainPos);
            return false;
        }
        if (!OccultismReflection.isIdle(be)) {
            LOG.debug("canDispatch: golden bowl busy at {}", mainPos);
            return false;
        }
        var goldenHandler = OccultismReflection.itemHandler(be);
        if (goldenHandler == null || !slotEmpty(goldenHandler)) {
            LOG.debug("canDispatch: golden bowl slot not empty at {}", mainPos);
            return false;
        }
        if (!OccultismReflection.hasValidPentacle(bind.recipe(), level, mainPos)) {
            LOG.debug("canDispatch: pentacle invalid for {} at {}", bind.recipe().getClass().getSimpleName(), mainPos);
            return false;
        }
        return true;
    }

    @Override
    @Nullable
    public DispatchPlan planWithBinding(ServerLevel level, BlockPos mainPos,
                                        IPatternDetails pattern, KeyCounter[] inputs,
                                        Object handle, IActionSource source) {
        if (!(handle instanceof OccultismBindHandle bind)) {
            return null;
        }

        var units = expandInputUnits(inputs);
        if (units == null || units.isEmpty()) {
            LOG.debug("planWithBinding: expandInputUnits null/empty at {}", mainPos);
            return null;
        }

        var match = matchInputsToRecipe(units, bind.recipe());
        if (match == null) {
            LOG.debug("planWithBinding: runtime inputs ({} units) didn't match bound recipe at {}",
                    units.size(), mainPos);
            return null;
        }

        var bowls = findEmptySacrificialBowls(level, mainPos, bind.recipe());
        if (bowls == null || bowls.size() < match.ingredients().size()) {
            LOG.debug("planWithBinding: not enough empty sacrificial bowls at {} (need {}, found {})",
                    mainPos, match.ingredients().size(), bowls == null ? -1 : bowls.size());
            return null;
        }

        var targets = new ArrayList<TargetSlot>(match.ingredients().size() + 1 + match.proxyCosts().size());
        for (int i = 0; i < match.ingredients().size(); i++) {
            var unit = match.ingredients().get(i);
            var bowl = bowls.get(i);
            targets.add(new TargetSlot(
                    level,
                    bowl.pos(),
                    null,
                    List.of(unit.toGenericStack()),
                    InsertionStrategy.CUSTOM,
                    sacrificialBowlInserter(level, bowl.pos(), unit)));
        }

        // The completesSacrifice / completesItemUse flags must be set on the
        // GOLDEN bowl (sacrificeProvided / itemUseProvided live there, not on
        // peripheral sacrificial bowls). They also must be applied *after*
        // GoldenSacrificialBowlBlockEntity#startRitual runs (it gets invoked
        // synchronously by insertItem on the activation slot, and it resets
        // both flags to false). So we plumb the requirement through to the
        // golden-bowl inserter and let it flip the flags immediately after
        // the activation item is committed.
        targets.add(new TargetSlot(
                level,
                mainPos,
                null,
                List.of(match.activation().toGenericStack()),
                InsertionStrategy.CUSTOM,
                goldenBowlInserter(level, mainPos, bind.recipe(), match.activation(),
                        match.completesSacrifice(), match.completesItemUse())));
        if (!match.proxyCosts().isEmpty()) {
            targets.add(new TargetSlot(
                    level,
                    mainPos,
                    null,
                    match.proxyCosts().stream().map(PlannedUnit::toGenericStack).toList(),
                    InsertionStrategy.CUSTOM,
                    proxyCostConsumer(match.proxyCosts())));
        }

        return new DispatchPlan(List.copyOf(targets), null);
    }

    @Override
    public List<GenericStack> extractOutputs(ServerLevel level, BlockPos mainPos,
                                             AllowedOutputFilter filter,
                                             IActionSource source) {
        var be = level.getBlockEntity(mainPos);
        if (be == null || !recognizesMain(level, mainPos, be) || !OccultismReflection.isIdle(be)) {
            return List.of();
        }

        for (int y = 1; y <= 3; y++) {
            var bowlPos = mainPos.above(y);
            if (!level.isLoaded(bowlPos)) {
                continue;
            }

            var bowlBe = level.getBlockEntity(bowlPos);
            if (bowlBe == null
                    || !OccultismReflection.isSacrificialBowl(bowlBe)
                    || !isUpsideDownBowl(bowlBe.getBlockState())) {
                continue;
            }

            var handler = OccultismReflection.itemHandler(bowlBe);
            if (handler == null || handler.getSlots() <= 0) {
                continue;
            }

            var stack = handler.getStackInSlot(0);
            if (stack.isEmpty()) {
                continue;
            }

            var key = AEItemKey.of(stack);
            if (!allowsAutoReturn(level, mainPos, filter, key)) {
                continue;
            }

            var extracted = handler.extractItem(0, stack.getCount(), false);
            if (extracted.isEmpty()) {
                continue;
            }
            return List.of(new GenericStack(AEItemKey.of(extracted), extracted.getCount()));
        }

        return List.of();
    }

    /**
     * Recipe search executed during {@link #bind}. Ritual outputs cover too
     * many side-effect categories (summons, possessions, dummy placeholders,
     * commands), so we don't compare outputs at all here &mdash; the pattern's
     * input shape is the canonical disambiguator.
     */
    @Nullable
    private static OccultismBindHandle findCandidateRecipe(ServerLevel level, BlockPos mainPos,
                                                            IPatternDetails pattern) {
        var patternUnits = patternInputUnits(pattern);
        if (patternUnits == null || patternUnits.isEmpty()) {
            LOG.debug("findCandidate: patternInputUnits null/empty at {} (pattern={})",
                    mainPos, pattern.getDefinition());
            return null;
        }
        if (LOG.isDebugEnabled()) {
            var sb = new StringBuilder();
            for (var u : patternUnits) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(u.key().getId());
            }
            var raw = new StringBuilder();
            for (var input : pattern.getInputs()) {
                var possible = input.getPossibleInputs();
                if (raw.length() > 0) raw.append(", ");
                if (possible.length == 0) {
                    raw.append("<empty>");
                } else {
                    raw.append(possible[0].what()).append(" x").append(possible[0].amount())
                            .append(" mult=").append(input.getMultiplier());
                }
            }
            LOG.debug("findCandidate: pattern inputs at {} expandedUnits=[{}] rawInputs=[{}]",
                    mainPos, sb, raw);
        }
        int scanned = 0;
        int pentacleFail = 0;
        int missingApi = 0;
        int inputMismatch = 0;
        int mismatchLogged = 0;
        for (var holder : recipes(level)) {
            scanned++;
            var recipe = holder.value();
            if (!OccultismReflection.hasValidPentacle(recipe, level, mainPos)) {
                pentacleFail++;
                continue;
            }
            var activation = OccultismReflection.getActivationItem(recipe);
            var ingredients = OccultismReflection.getIngredients(recipe);
            if (activation == null || ingredients == null) {
                missingApi++;
                continue;
            }
            if (matchInputsToRecipe(patternUnits, recipe) == null) {
                inputMismatch++;
                if (mismatchLogged < 5) {
                    LOG.debug("findCandidate: recipe {} mismatch (activation={}, ingredients={}, requiresSacrifice={}, requiresItemUse={}, itemToUse={})",
                            holder.id(),
                            ingredientFirstItems(activation),
                            ingredientListItems(ingredients),
                            OccultismReflection.requiresSacrifice(recipe),
                            OccultismReflection.requiresItemUse(recipe),
                            ingredientFirstItems(OccultismReflection.getItemToUse(recipe)));
                    mismatchLogged++;
                }
                continue;
            }
            LOG.debug("findCandidate: matched recipe {} at {} ({} pattern units)",
                    holder.id(), mainPos, patternUnits.size());
            return new OccultismBindHandle(recipe);
        }
        LOG.debug("findCandidate: no match at {} (scanned={}, pentacleFail={}, missingApi={}, inputMismatch={}, patternUnits={})",
                mainPos, scanned, pentacleFail, missingApi, inputMismatch, patternUnits.size());
        return null;
    }

    private static String ingredientFirstItems(@Nullable Ingredient ingredient) {
        if (ingredient == null) {
            return "<null>";
        }
        try {
            var items = ingredient.getItems();
            if (items.length == 0) {
                return "<empty>";
            }
            var sb = new StringBuilder();
            for (int i = 0; i < Math.min(3, items.length); i++) {
                if (sb.length() > 0) sb.append("|");
                sb.append(BuiltInRegistries.ITEM.getKey(items[i].getItem()));
            }
            if (items.length > 3) sb.append("...");
            return sb.toString();
        } catch (RuntimeException | LinkageError ignored) {
            return "<error>";
        }
    }

    private static String ingredientListItems(List<Ingredient> ingredients) {
        var sb = new StringBuilder("[");
        for (int i = 0; i < ingredients.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(ingredientFirstItems(ingredients.get(i)));
        }
        return sb.append("]").toString();
    }

    /**
     * Per-push input matching: pairs runtime inputs with the bound recipe's
     * activation + ingredient ingredients. Cheap because the recipe is already
     * locked in.
     */
    @Nullable
    private static InputMatch matchInputsToRecipe(List<PlannedUnit> units, Object recipe) {
        var activation = OccultismReflection.getActivationItem(recipe);
        var ingredients = OccultismReflection.getIngredients(recipe);
        if (activation == null || ingredients == null) {
            return null;
        }

        for (int i = 0; i < units.size(); i++) {
            if (!activation.test(units.get(i).stack())) {
                continue;
            }
            var match = matchInputsWithActivation(units, i, ingredients, recipe);
            if (match != null) {
                return match;
            }
        }
        return null;
    }

    @Nullable
    private static InputMatch matchInputsWithActivation(List<PlannedUnit> units,
                                                        int activationIndex,
                                                        List<Ingredient> ingredients,
                                                        Object recipe) {
        var used = new boolean[units.size()];
        var matchedActivation = units.get(activationIndex);
        used[activationIndex] = true;

        var matchedIngredients = new ArrayList<PlannedUnit>(ingredients.size());
        for (var ingredient : ingredients) {
            PlannedUnit matched = null;
            for (int i = 0; i < units.size(); i++) {
                if (!used[i] && ingredient.test(units.get(i).stack())) {
                    matched = units.get(i);
                    used[i] = true;
                    break;
                }
            }
            if (matched == null) {
                return null;
            }
            matchedIngredients.add(matched);
        }

        boolean completesSacrifice = false;
        boolean completesItemUse = false;
        var proxyCosts = new ArrayList<PlannedUnit>(2);
        var itemToUse = OccultismReflection.getItemToUse(recipe);
        boolean requiresSacrifice = OccultismReflection.requiresSacrifice(recipe);
        boolean requiresItemUse = itemToUse != null && OccultismReflection.requiresItemUse(recipe);
        for (int i = 0; i < units.size(); i++) {
            if (used[i]) {
                continue;
            }
            var unit = units.get(i);
            if (!completesSacrifice && requiresSacrifice && isSpawnEgg(unit.stack())) {
                completesSacrifice = true;
                used[i] = true;
                proxyCosts.add(unit);
                continue;
            }
            if (!completesItemUse && requiresItemUse && itemToUse.test(unit.stack())) {
                completesItemUse = true;
                used[i] = true;
                proxyCosts.add(unit);
                continue;
            }
            return null;
        }

        return new InputMatch(matchedActivation, List.copyOf(matchedIngredients),
                completesSacrifice, completesItemUse, List.copyOf(proxyCosts));
    }

    @Nullable
    private static List<BowlSlot> findEmptySacrificialBowls(ServerLevel level, BlockPos mainPos, Object recipe) {
        var bowls = OccultismReflection.getSacrificialBowls(recipe, level, mainPos);
        if (bowls == null) {
            return null;
        }

        var result = new ArrayList<BowlSlot>();
        for (var bowl : bowls) {
            if (!(bowl instanceof BlockEntity bowlBe)) {
                return null;
            }
            if (bowlBe.getBlockPos().equals(mainPos) || isUpsideDownBowl(bowlBe.getBlockState())) {
                continue;
            }

            var handler = OccultismReflection.itemHandler(bowlBe);
            if (handler == null || handler.getSlots() <= 0) {
                return null;
            }
            if (!handler.getStackInSlot(0).isEmpty()) {
                return null;
            }
            result.add(new BowlSlot(bowlBe.getBlockPos()));
        }
        return result;
    }

    private static BiFunction<GenericStack, Actionable, Long> sacrificialBowlInserter(
            ServerLevel level, BlockPos pos, PlannedUnit unit) {
        return (stack, mode) -> {
            if (!matchesPlannedStack(stack, unit)) {
                return 0L;
            }

            var be = level.getBlockEntity(pos);
            if (be == null
                    || !OccultismReflection.isSacrificialBowl(be)
                    || isUpsideDownBowl(be.getBlockState())) {
                return 0L;
            }

            var handler = OccultismReflection.itemHandler(be);
            if (handler == null || handler.getSlots() <= 0 || !handler.getStackInSlot(0).isEmpty()) {
                return 0L;
            }

            if (mode == Actionable.MODULATE) {
                var remainder = handler.insertItem(0, unit.stack(), false);
                var accepted = 1L - remainder.getCount();
                if (accepted > 0) {
                    be.setChanged();
                    level.updateNeighborsAt(pos, be.getBlockState().getBlock());
                }
                return accepted;
            }
            return 1L;
        };
    }

    private static BiFunction<GenericStack, Actionable, Long> goldenBowlInserter(
            ServerLevel level, BlockPos pos, Object recipe, PlannedUnit unit,
            boolean completesSacrifice, boolean completesItemUse) {
        return (stack, mode) -> {
            if (!matchesPlannedStack(stack, unit)) {
                return 0L;
            }

            var be = level.getBlockEntity(pos);
            if (be == null
                    || !OccultismReflection.isGoldenBowl(be)
                    || !OccultismReflection.isIdle(be)
                    || !OccultismReflection.hasValidPentacle(recipe, level, pos)) {
                return 0L;
            }

            var handler = OccultismReflection.itemHandler(be);
            if (handler == null || handler.getSlots() <= 0 || !handler.getStackInSlot(0).isEmpty()) {
                return 0L;
            }

            if (mode == Actionable.MODULATE) {
                var remainder = handler.insertItem(0, unit.stack(), false);
                var accepted = 1L - remainder.getCount();
                if (accepted > 0 && (completesSacrifice || completesItemUse)) {
                    // insertItem above synchronously calls startRitual() on
                    // success, which resets sacrificeProvided and
                    // itemUseProvided. Flip them back here so the ritual tick
                    // doesn't stall waiting for an in-world sacrifice / item
                    // use that the proxy items already represent.
                    OccultismReflection.completeDeferredRequirements(
                            be, completesSacrifice, completesItemUse);
                    be.setChanged();
                }
                return accepted;
            }
            return 1L;
        };
    }

    private static BiFunction<GenericStack, Actionable, Long> proxyCostConsumer(List<PlannedUnit> units) {
        return (stack, mode) -> {
            for (var unit : units) {
                if (matchesPlannedStack(stack, unit)) {
                    return 1L;
                }
            }
            return 0L;
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
        return units;
    }

    @Nullable
    private static List<PlannedUnit> patternInputUnits(IPatternDetails pattern) {
        var inputs = pattern.getInputs();
        var units = new ArrayList<PlannedUnit>();
        long total = 0;
        for (var input : inputs) {
            var possible = input.getPossibleInputs();
            if (possible.length == 0 || !(possible[0].what() instanceof AEItemKey itemKey)) {
                continue;
            }
            // AE2's IInput stores the per-craft quantity in two places: the
            // primary {@link GenericStack#amount} of {@code possibleInputs[0]}
            // (typically 1 for items, 1000 for fluids) and a separate
            // {@link IInput#getMultiplier()} that scales it. The true count of
            // items the pattern wants is multiplier * primary.amount(). Using
            // only primary.amount() collapses any stack > 1 into a single unit,
            // which was the bug that caused "iron_ingot x4" to look like one
            // pattern unit.
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


    private static boolean matchesPlannedStack(GenericStack stack, PlannedUnit unit) {
        return stack.amount() == 1 && unit.key().equals(stack.what());
    }

    private static boolean slotEmpty(IItemHandler handler) {
        return handler.getSlots() > 0 && handler.getStackInSlot(0).isEmpty();
    }

    private static boolean isUpsideDownBowl(BlockState state) {
        return state.hasProperty(BlockStateProperties.FACING)
                && state.getValue(BlockStateProperties.FACING) == Direction.DOWN;
    }

    private static boolean isSpawnEgg(ItemStack stack) {
        return stack.getItem() instanceof SpawnEggItem;
    }

    private static List<RecipeHolder<?>> recipes(ServerLevel level) {
        return BuiltInRegistries.RECIPE_TYPE.getOptional(RITUAL_RECIPE_TYPE)
                .map(type -> recipesForType(level, type))
                .orElse(List.of());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static List<RecipeHolder<?>> recipesForType(ServerLevel level, RecipeType<?> type) {
        return (List<RecipeHolder<?>>) (List<?>) level.getRecipeManager()
                .getAllRecipesFor((RecipeType) type);
    }

    private static boolean isOccultismLoaded() {
        return ModList.get().isLoaded(MOD_ID);
    }

    private static ResourceLocation occultismId(String path) {
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

    /**
     * Opaque handle returned from {@link #bind}. Caches the matched recipe so
     * {@code canDispatch} / {@code planWithBinding} can skip the recipe-table
     * scan entirely.
     */
    private record OccultismBindHandle(Object recipe) {
    }

    /** Per-push input-to-ingredient match result. */
    private record InputMatch(PlannedUnit activation, List<PlannedUnit> ingredients,
                              boolean completesSacrifice, boolean completesItemUse,
                              List<PlannedUnit> proxyCosts) {
    }

    private record BowlSlot(BlockPos pos) {
    }

    private static final class OccultismReflection {
        private static final String GOLDEN_BOWL_CLASS =
                "com.klikli_dev.occultism.common.blockentity.GoldenSacrificialBowlBlockEntity";
        private static final String SACRIFICIAL_BOWL_CLASS =
                "com.klikli_dev.occultism.common.blockentity.SacrificialBowlBlockEntity";
        private static final String RITUAL_RECIPE_CLASS =
                "com.klikli_dev.occultism.crafting.recipe.RitualRecipe";
        private static final String RITUAL_CLASS =
                "com.klikli_dev.occultism.common.ritual.Ritual";

        private static volatile boolean lookupDone;
        private static volatile @Nullable Class<?> goldenBowlClass;
        private static volatile @Nullable Class<?> sacrificialBowlClass;
        private static volatile @Nullable Method getCurrentRitualRecipeMethod;
        private static volatile @Nullable Field itemStackHandlerField;
        private static volatile @Nullable Method getActivationItemMethod;
        private static volatile @Nullable Method getIngredientsMethod;
        private static volatile @Nullable Method getRitualTypeMethod;
        private static volatile @Nullable Method requiresSacrificeMethod;
        private static volatile @Nullable Method requiresItemUseMethod;
        private static volatile @Nullable Method getItemToUseMethod;
        private static volatile @Nullable Method getPentacleMethod;
        private static volatile @Nullable Method getRitualMethod;
        private static volatile @Nullable Method getSacrificialBowlsMethod;
        private static volatile @Nullable Field sacrificeProvidedField;
        private static volatile @Nullable Field itemUseProvidedField;

        static boolean isGoldenBowl(Object object) {
            ensureLookup();
            return goldenBowlClass != null && goldenBowlClass.isInstance(object);
        }

        static boolean isSacrificialBowl(Object object) {
            ensureLookup();
            return sacrificialBowlClass != null && sacrificialBowlClass.isInstance(object);
        }

        static boolean isIdle(Object goldenBowl) {
            ensureLookup();
            if (getCurrentRitualRecipeMethod == null) {
                return false;
            }
            try {
                return getCurrentRitualRecipeMethod.invoke(goldenBowl) == null;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                return false;
            }
        }

        static boolean requiresSacrifice(Object recipe) {
            ensureLookup();
            if (requiresSacrificeMethod == null) {
                return false;
            }
            try {
                var value = requiresSacrificeMethod.invoke(recipe);
                return value instanceof Boolean b && b;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                return false;
            }
        }

        static boolean requiresItemUse(Object recipe) {
            ensureLookup();
            if (requiresItemUseMethod == null) {
                return false;
            }
            try {
                var value = requiresItemUseMethod.invoke(recipe);
                return value instanceof Boolean b && b;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                return false;
            }
        }

        @Nullable
        static Ingredient getItemToUse(Object recipe) {
            ensureLookup();
            if (getItemToUseMethod == null) {
                return null;
            }
            try {
                var value = getItemToUseMethod.invoke(recipe);
                return value instanceof Ingredient ingredient && !ingredient.isEmpty() ? ingredient : null;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                return null;
            }
        }

        static void completeDeferredRequirements(Object goldenBowl, boolean sacrifice, boolean itemUse) {
            ensureLookup();
            try {
                if (sacrifice) {
                    if (sacrificeProvidedField != null) {
                        sacrificeProvidedField.setBoolean(goldenBowl, true);
                        LOG.debug("completeDeferredRequirements: sacrificeProvided=true on {}", goldenBowl);
                    } else {
                        LOG.warn("completeDeferredRequirements: sacrificeProvidedField is null; "
                                + "ritual will stall on sacrifice gate");
                    }
                }
                if (itemUse) {
                    if (itemUseProvidedField != null) {
                        itemUseProvidedField.setBoolean(goldenBowl, true);
                        LOG.debug("completeDeferredRequirements: itemUseProvided=true on {}", goldenBowl);
                    } else {
                        LOG.warn("completeDeferredRequirements: itemUseProvidedField is null; "
                                + "ritual will stall on item-use gate");
                    }
                }
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                LOG.warn("completeDeferredRequirements failed (sacrifice={}, itemUse={}): {}",
                        sacrifice, itemUse, e.toString());
            }
        }

        @Nullable
        static IItemHandler itemHandler(Object bowl) {
            ensureLookup();
            if (itemStackHandlerField == null) {
                return null;
            }
            try {
                var value = itemStackHandlerField.get(bowl);
                return value instanceof IItemHandler handler ? handler : null;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                return null;
            }
        }

        @Nullable
        static Ingredient getActivationItem(Object recipe) {
            ensureLookup();
            if (getActivationItemMethod == null) {
                return null;
            }
            try {
                var value = getActivationItemMethod.invoke(recipe);
                return value instanceof Ingredient ingredient ? ingredient : null;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                return null;
            }
        }

        @Nullable
        static List<Ingredient> getIngredients(Object recipe) {
            ensureLookup();
            if (getIngredientsMethod == null) {
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
                return List.copyOf(result);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                return null;
            }
        }

        @Nullable
        static ResourceLocation getRitualType(Object recipe) {
            ensureLookup();
            if (getRitualTypeMethod == null) {
                return null;
            }
            try {
                var value = getRitualTypeMethod.invoke(recipe);
                return value instanceof ResourceLocation id ? id : null;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                return null;
            }
        }

        static boolean hasValidPentacle(Object recipe, Level level, BlockPos pos) {
            var pentacle = getPentacle(recipe);
        if (pentacle == null) {
            return false;
        }
        try {
            var validate = ReflectionSupport.findMethodCached(pentacle.getClass(), "validate", Level.class, BlockPos.class)
                    .orElse(null);
            if (validate == null) {
                return false;
            }
            return validate.invoke(pentacle, level, pos) != null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return false;
        }
    }

        @Nullable
        static List<?> getSacrificialBowls(Object recipe, Level level, BlockPos pos) {
            ensureLookup();
            if (getRitualMethod == null || getSacrificialBowlsMethod == null) {
                return null;
            }
            try {
                var ritual = getRitualMethod.invoke(recipe);
                var value = getSacrificialBowlsMethod.invoke(ritual, level, pos);
                return value instanceof List<?> list ? list : null;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                return null;
            }
        }

        @Nullable
        private static Object getPentacle(Object recipe) {
            ensureLookup();
            if (getPentacleMethod == null) {
                return null;
            }
            try {
                return getPentacleMethod.invoke(recipe);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                return null;
            }
        }

        private static void ensureLookup() {
            if (lookupDone) {
                return;
            }
            synchronized (OccultismReflection.class) {
                if (lookupDone) {
                    return;
                }
                doLookup();
                lookupDone = true;
            }
        }

        private static void doLookup() {
            goldenBowlClass = tryClass(GOLDEN_BOWL_CLASS);
            sacrificialBowlClass = tryClass(SACRIFICIAL_BOWL_CLASS);
            var ritualRecipeClass = tryClass(RITUAL_RECIPE_CLASS);
            var ritualClass = tryClass(RITUAL_CLASS);

            if (goldenBowlClass != null) {
                getCurrentRitualRecipeMethod = tryMethod(goldenBowlClass, "getCurrentRitualRecipe");
                sacrificeProvidedField = tryField(goldenBowlClass, "sacrificeProvided");
                itemUseProvidedField = tryField(goldenBowlClass, "itemUseProvided");
            }
            if (sacrificialBowlClass != null) {
                itemStackHandlerField = tryField(sacrificialBowlClass, "itemStackHandler");
            }
            if (ritualRecipeClass != null) {
                getActivationItemMethod = tryMethod(ritualRecipeClass, "getActivationItem");
                getIngredientsMethod = tryMethod(ritualRecipeClass, "getIngredients");
                getRitualTypeMethod = tryMethod(ritualRecipeClass, "getRitualType");
                requiresSacrificeMethod = tryMethod(ritualRecipeClass, "requiresSacrifice");
                requiresItemUseMethod = tryMethod(ritualRecipeClass, "requiresItemUse");
                getItemToUseMethod = tryMethod(ritualRecipeClass, "getItemToUse");
                getPentacleMethod = tryMethod(ritualRecipeClass, "getPentacle");
                getRitualMethod = tryMethod(ritualRecipeClass, "getRitual");
            }
            if (ritualClass != null) {
                getSacrificialBowlsMethod = tryMethod(ritualClass, "getSacrificialBowls",
                        Level.class, BlockPos.class);
            }
        }

        @Nullable
        private static Class<?> tryClass(String name) {
            try {
                return Class.forName(name);
            } catch (ClassNotFoundException | LinkageError ignored) {
                return null;
            }
        }

        @Nullable
        private static Method tryMethod(Class<?> declaring, String name, Class<?>... params) {
            try {
                return declaring.getMethod(name, params);
            } catch (NoSuchMethodException | SecurityException | LinkageError ignored) {
                return null;
            }
        }

        @Nullable
        private static Field tryField(Class<?> declaring, String name) {
            try {
                return declaring.getField(name);
            } catch (NoSuchFieldException | SecurityException | LinkageError ignored) {
                return null;
            }
        }
    }
}
