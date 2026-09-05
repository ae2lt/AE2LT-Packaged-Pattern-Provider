package com.moakiee.ae2lt.packaged.logic.multiblock.malum;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Consumer;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.fml.ModList;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;

import com.moakiee.ae2lt.packaged.patternprovider.AllowedOutputFilter;
import com.moakiee.ae2lt.packaged.logic.multiblock.AcceptedInsertion;
import com.moakiee.ae2lt.packaged.logic.multiblock.AdapterPersistentScope;
import com.moakiee.ae2lt.packaged.logic.multiblock.AdapterRecipeTypes;
import com.moakiee.ae2lt.packaged.logic.multiblock.DispatchCommitException;
import com.moakiee.ae2lt.packaged.logic.multiblock.DispatchPlan;
import com.moakiee.ae2lt.packaged.logic.multiblock.AdapterBlocks;
import com.moakiee.ae2lt.packaged.logic.multiblock.InsertionStrategy;
import com.moakiee.ae2lt.packaged.logic.multiblock.MultiblockAdapter;
import com.moakiee.ae2lt.packaged.logic.multiblock.TargetSlot;
import com.moakiee.ae2lt.packaged.logic.multiblock.binding.BindingMode;
import com.moakiee.ae2lt.packaged.logic.multiblock.binding.BindingResult;

/**
 * Runtime adapter for Malum's Spirit Crucible focusing.
 * The crucible catalyst is reusable, but it is supplied as part of each
 * pattern dispatch. The plan inserts the catalyst first, then the spirit
 * shards, and extracts the catalyst again once the crucible is inactive.
 */
public final class MalumSpiritFocusingAdapter implements MultiblockAdapter {

    private static final String MOD_ID = "malum";
    private static final String CATALYST_FLAG_PREFIX =
            "malum_spirit_focusing:catalyst:";
    private static final String CATALYST_STATE =
            "malum_spirit_focusing:catalyst";
    private static final String OUTPUT_STATE =
            "malum_spirit_focusing:output_entities";
    private static final ResourceLocation CRUCIBLE_BLOCK = malumId("spirit_crucible");
    private static final ResourceLocation CRUCIBLE_COMPONENT_BLOCK = malumId("spirit_crucible_component");
    private static final ResourceLocation RECIPE_TYPE_ID = malumId("spirit_focusing");
    private static final int MAX_INPUT_AMOUNT = 128;

    @Override
    public int priority() {
        return 100;
    }

    @Override
    public boolean recognizesMain(ServerLevel level, BlockPos pos, BlockEntity be) {
        return be != null
                && isMalumLoaded()
                && blockId(be.getBlockState()).equals(CRUCIBLE_BLOCK)
                && MalumReflection.isSpiritCrucible(be);
    }

    @Override
    public ResourceLocation requiredAdapterId(ServerLevel level, BlockPos pos) {
        return com.moakiee.ae2lt.packaged.item.AdapterIds.MALUM_SPIRIT_FOCUSING;
    }

    @Override
    @Nullable
    public BindingResult bind(ServerLevel level, BlockPos mainPos, IPatternDetails pattern) {
        var be = level.getBlockEntity(mainPos);
        if (be == null || !recognizesMain(level, mainPos, be) || !hasCrucibleComponent(level, mainPos)) {
            return null;
        }
        if (!hasSingleItemOutput(pattern)) {
            return null;
        }
        var recipes = findCandidateRecipes(level, pattern);
        return recipes.isEmpty()
                ? null
                : new BindingResult(new FocusingBindHandle(recipes), BindingMode.REAL);
    }

    @Override
    public boolean canDispatch(ServerLevel level, BlockPos mainPos, Object handle,
                               AdapterPersistentScope scope) {
        if (MalumDroppedItemOwnership.blocksDispatch(
                level, mainPos, scope, OUTPUT_STATE)) {
            var ownership = MalumDroppedItemOwnership.load(
                    level, mainPos, scope, OUTPUT_STATE);
            var be = level.getBlockEntity(mainPos);
            var spiritInventory = be == null ? null : MalumReflection.crucibleSpiritInventory(be);
            if (ownership != null
                    && be != null
                    && MalumReflection.isCrucibleIdle(be)
                    && spiritInventory != null
                    && !MalumAdapterSupport.inventoryEmpty(spiritInventory)) {
                MalumReflection.updateCrucibleRecipe(
                        level, mainPos, ownership.recipeId());
            }
            return false;
        }
        return canDispatch(level, mainPos, handle);
    }

    @Override
    public void tickPending(ServerLevel level, BlockPos mainPos,
                            AdapterPersistentScope scope) {
        var ownership = MalumDroppedItemOwnership.load(
                level, mainPos, scope, OUTPUT_STATE);
        if (ownership == null) {
            return;
        }
        if (MalumDroppedItemOwnership.expired(level, ownership)) {
            MalumDroppedItemOwnership.logStale(level, mainPos, ownership, OUTPUT_STATE);
        }
        var be = level.getBlockEntity(mainPos);
        if (be == null
                || !recognizesMain(level, mainPos, be)
                || !hasCrucibleComponent(level, mainPos)
                || !MalumReflection.isCrucibleIdle(be)) {
            return;
        }
        var catalystInventory = MalumReflection.crucibleInventory(be);
        var spiritInventory = MalumReflection.crucibleSpiritInventory(be);
        if (catalystInventory == null
                || catalystInventory.getSlots() < 1
                || catalystInventory.getStackInSlot(0).isEmpty()
                || spiritInventory == null
                || MalumAdapterSupport.inventoryEmpty(spiritInventory)) {
            return;
        }
        MalumReflection.updateCrucibleRecipe(
                level, mainPos, ownership.recipeId());
    }

    @Override
    public boolean canDispatch(ServerLevel level, BlockPos mainPos, Object handle) {
        if (!(handle instanceof FocusingBindHandle bind) || bind.recipes().isEmpty()) {
            return false;
        }
        var be = level.getBlockEntity(mainPos);
        if (be == null || !recognizesMain(level, mainPos, be) || !hasCrucibleComponent(level, mainPos)) {
            return false;
        }
        if (!MalumReflection.isCrucibleIdle(be)) {
            return false;
        }
        var catalystInventory = MalumReflection.crucibleInventory(be);
        var spiritInventory = MalumReflection.crucibleSpiritInventory(be);
        return catalystInventory != null
                && spiritInventory != null
                && catalystInventory.getSlots() >= 1
                && MalumAdapterSupport.inventoryEmpty(catalystInventory)
                && MalumAdapterSupport.inventoryEmpty(spiritInventory);
    }

    @Override
    @Nullable
    public DispatchPlan planWithBinding(ServerLevel level, BlockPos mainPos,
                                        IPatternDetails pattern, KeyCounter[] inputs,
                                        Object handle, IActionSource source) {
        return planWithBinding(level, mainPos, pattern, inputs, handle, source,
                com.moakiee.ae2lt.packaged.logic.multiblock.AdapterPersistentScope.NOOP);
    }

    @Override
    @Nullable
    public DispatchPlan planWithBinding(ServerLevel level, BlockPos mainPos,
                                        IPatternDetails pattern, KeyCounter[] inputs,
                                        Object handle, IActionSource source,
                                        com.moakiee.ae2lt.packaged.logic.multiblock.AdapterPersistentScope scope) {
        if (!(handle instanceof FocusingBindHandle bind)
                || MalumDroppedItemOwnership.blocksDispatch(
                        level, mainPos, scope, OUTPUT_STATE)) {
            return null;
        }
        var be = level.getBlockEntity(mainPos);
        if (be == null || !recognizesMain(level, mainPos, be) || !hasCrucibleComponent(level, mainPos)) {
            return null;
        }
        if (!MalumReflection.isCrucibleIdle(be)) {
            return null;
        }

        var catalystInventory = MalumReflection.crucibleInventory(be);
        var spiritInventory = MalumReflection.crucibleSpiritInventory(be);
        if (catalystInventory == null
                || spiritInventory == null
                || catalystInventory.getSlots() < 1
                || !MalumAdapterSupport.inventoryEmpty(catalystInventory)
                || !MalumAdapterSupport.inventoryEmpty(spiritInventory)) {
            return null;
        }

        var aggregatedInputs = MalumAdapterSupport.aggregateInputs(inputs, MAX_INPUT_AMOUNT);
        if (aggregatedInputs == null) {
            return null;
        }

        var match = findRecipeMatch(bind.recipes(), aggregatedInputs);
        if (match == null || match.spirits().size() > spiritInventory.getSlots()) {
            return null;
        }

        var preexistingEntities = MalumDroppedItemOwnership.capture(
                level, crucibleOutputAabb(mainPos), match.output());
        var targets = new ArrayList<TargetSlot>(1 + match.spirits().size());
        targets.add(new TargetSlot(
                level,
                mainPos,
                null,
                List.of(MalumAdapterSupport.toGenericStack(match.catalyst())),
                InsertionStrategy.CUSTOM,
                catalystInserter(level, mainPos, match.catalystIngredient(), match.catalyst())));

        for (int i = 0; i < match.spirits().size(); i++) {
            var assignment = match.spirits().get(i);
            targets.add(new TargetSlot(
                    level,
                    mainPos,
                    null,
                    List.of(MalumAdapterSupport.toGenericStack(assignment)),
                    InsertionStrategy.CUSTOM,
                    spiritInserter(level, mainPos, match.catalystIngredient(), i, assignment)));
        }

        return new DispatchPlan(
                List.copyOf(targets),
                () -> {
                    var catalyst = match.catalyst().value().toStack(
                            (int) match.catalyst().amount());
                    scope.setState(mainPos, catalystStateKey(level), stableCatalystFingerprint(catalyst));
                    MalumDroppedItemOwnership.store(
                            level, mainPos, scope, OUTPUT_STATE, preexistingEntities,
                            match.output(), match.recipeId());
                    if (!MalumReflection.updateCrucibleRecipe(
                            level, mainPos, match.recipeId())) {
                        throw new DispatchCommitException(
                                "Malum Spirit Crucible did not activate the expected recipe "
                                        + match.recipeId());
                    }
                },
                (accepted, recovered) -> recoverPartialDispatch(
                        level, mainPos, scope, match, targets, accepted, recovered));
    }

    @Override
    public List<GenericStack> extractOutputs(ServerLevel level, BlockPos mainPos,
                                             AllowedOutputFilter filter,
                                             IActionSource source) {
        return extractOutputs(level, mainPos, filter, source,
                com.moakiee.ae2lt.packaged.logic.multiblock.AdapterPersistentScope.NOOP);
    }

    @Override
    public List<GenericStack> extractOutputs(ServerLevel level, BlockPos mainPos,
                                             AllowedOutputFilter filter,
                                             IActionSource source,
                                             AdapterPersistentScope scope) {
        var be = level.getBlockEntity(mainPos);
        if (be == null || !recognizesMain(level, mainPos, be)
                || !MalumReflection.isCrucibleIdle(be)) {
            return List.of();
        }
        var catalystInventory = MalumReflection.crucibleInventory(be);
        var spiritInventory = MalumReflection.crucibleSpiritInventory(be);
        if (catalystInventory == null
                || catalystInventory.getSlots() < 1
                || spiritInventory == null) {
            return List.of();
        }

        var ownership = MalumDroppedItemOwnership.load(
                level, mainPos, scope, OUTPUT_STATE);
        if (ownership == null) {
            return List.of();
        }
        if (MalumDroppedItemOwnership.expired(level, ownership)) {
            MalumDroppedItemOwnership.logStale(level, mainPos, ownership, OUTPUT_STATE);
        }
        if (!MalumAdapterSupport.inventoryEmpty(spiritInventory)) {
            MalumReflection.updateCrucibleRecipe(
                    level, mainPos, ownership.recipeId());
            return List.of();
        }
        var outputs = new ArrayList<GenericStack>();
        var currentCatalyst = catalystInventory.getStackInSlot(0);
        var legacyFlag = currentCatalyst.isEmpty() ? null : catalystFlag(currentCatalyst);
        var expectedCatalyst = scope.getState(mainPos, catalystStateKey(level));
        if (!currentCatalyst.isEmpty()) {
            boolean ownedCatalyst = stableCatalystFingerprint(currentCatalyst)
                    .equals(expectedCatalyst)
                    || (legacyFlag != null && scope.hasFlag(mainPos, legacyFlag));
            if (!ownedCatalyst) {
                return List.of();
            }
            var catalyst = extractReusableCatalyst(level, be);
            if (catalyst.isEmpty()) {
                return List.of();
            }
            outputs.add(new GenericStack(AEItemKey.of(catalyst), catalyst.getCount()));
        }
        clearCatalystState(level, mainPos, scope, legacyFlag);

        var product = MalumDroppedItemOwnership.collectNewOutputs(
                level, crucibleOutputAabb(mainPos), ownership);
        outputs.addAll(product);
        if (!product.isEmpty()) {
            MalumDroppedItemOwnership.clear(level, mainPos, scope, OUTPUT_STATE);
        }
        return List.copyOf(outputs);
    }

    private static void recoverPartialDispatch(
            ServerLevel level,
            BlockPos mainPos,
            AdapterPersistentScope scope,
            RecipeMatch match,
            List<TargetSlot> targets,
            List<AcceptedInsertion> acceptedInsertions,
            Consumer<GenericStack> recovered) {
        var be = level.getBlockEntity(mainPos);
        if (be == null || !isCrucibleStillUnstarted(be)) {
            return;
        }
        int recoveredCount = 0;
        for (var accepted : acceptedInsertions) {
            int targetIndex = targets.indexOf(accepted.target());
            if (targetIndex < 0) {
                continue;
            }
            GenericStack stack = null;
            if (targetIndex == 0) {
                var inventory = MalumReflection.crucibleInventory(be);
                if (inventory != null) {
                    stack = MalumAdapterSupport.recoverExactInsertion(
                            inventory, 0, accepted.stack());
                }
            } else if (targetIndex <= match.spirits().size()) {
                var inventory = MalumReflection.crucibleSpiritInventory(be);
                if (inventory != null) {
                    stack = MalumAdapterSupport.recoverExactInsertion(
                            inventory, targetIndex - 1, accepted.stack());
                }
            }
            if (stack != null) {
                recovered.accept(stack);
                recoveredCount++;
            }
        }
        if (recoveredCount == acceptedInsertions.size()) {
            clearOwnedState(level, mainPos, scope, null);
        }
    }

    private static void clearOwnedState(ServerLevel level, BlockPos mainPos,
                                        AdapterPersistentScope scope,
                                        @Nullable String legacyFlag) {
        clearCatalystState(level, mainPos, scope, legacyFlag);
        MalumDroppedItemOwnership.clear(level, mainPos, scope, OUTPUT_STATE);
    }

    private static void clearCatalystState(ServerLevel level, BlockPos mainPos,
                                           AdapterPersistentScope scope,
                                           @Nullable String legacyFlag) {
        scope.clearState(mainPos, catalystStateKey(level));
        scope.clearState(mainPos, CATALYST_STATE);
        if (legacyFlag != null) {
            scope.clearFlag(mainPos, legacyFlag);
        }
    }

    private static boolean isCrucibleStillUnstarted(BlockEntity be) {
        return MalumReflection.isCrucibleIdle(be);
    }

    @Nullable
    private static RecipeMatch findRecipeMatch(List<Object> recipes,
                                               List<MalumRecipeInputMatcher.Input<AEItemKey>> inputs) {
        for (var recipe : recipes) {
            var input = MalumReflection.focusingInput(recipe);
            if (input == null) {
                continue;
            }

            var spiritStacks = MalumReflection.focusingSpirits(recipe);
            var spiritRequirements = spiritStacks == null
                    ? null
                    : MalumAdapterSupport.requirementsFromStacks(spiritStacks);
            if (spiritRequirements == null) {
                continue;
            }

            var match = matchFocusingInputs(
                    inputs,
                    MalumAdapterSupport.requirement(input, 1),
                    spiritRequirements);
            var output = MalumReflection.focusingOutput(recipe);
            var recipeId = MalumReflection.recipeId(recipe);
            if (match != null && output != null && !output.isEmpty() && recipeId != null) {
                return new RecipeMatch(
                        recipeId, input, match.catalyst(), match.spirits(), output);
            }
        }
        return null;
    }

    @Nullable
    static <T> FocusInputMatch<T> matchFocusingInputs(
            List<MalumRecipeInputMatcher.Input<T>> inputs,
            MalumRecipeInputMatcher.Requirement<T> catalyst,
            List<MalumRecipeInputMatcher.Requirement<T>> spirits) {
        var match = MalumRecipeInputMatcher.match(inputs, catalyst, spirits, List.of());
        return match == null ? null : new FocusInputMatch<>(match.main(), match.spirits());
    }

    private static ItemStack extractReusableCatalyst(ServerLevel level, BlockEntity be) {
        if (!MalumReflection.isCrucibleIdle(be)) {
            return ItemStack.EMPTY;
        }
        var inventory = MalumReflection.crucibleInventory(be);
        if (inventory == null || inventory.getSlots() < 1) {
            return ItemStack.EMPTY;
        }
        var stack = inventory.getStackInSlot(0);
        if (stack.isEmpty() || !isKnownFocusingCatalyst(level, stack)) {
            return ItemStack.EMPTY;
        }
        return inventory.extractItem(0, stack.getCount(), false);
    }

    private static boolean isKnownFocusingCatalyst(ServerLevel level, ItemStack stack) {
        for (var recipe : recipes(level)) {
            var input = MalumReflection.focusingInput(recipe);
            if (input != null && input.test(stack)) {
                return true;
            }
        }
        return false;
    }

    private static String stableCatalystFingerprint(ItemStack stack) {
        var tag = new CompoundTag();
        stack.save(tag);
        if (tag.contains("tag", net.minecraft.nbt.Tag.TAG_COMPOUND)) {
            var itemTag = tag.getCompound("tag").copy();
            itemTag.remove("Damage");
            if (itemTag.isEmpty()) {
                tag.remove("tag");
            } else {
                tag.put("tag", itemTag);
            }
        }
        return tag.toString();
    }

    private static String catalystFingerprint(ItemStack stack) {
        var tag = new CompoundTag();
        stack.save(tag);
        return tag.toString();
    }

    private static String catalystStateKey(ServerLevel level) {
        return CATALYST_STATE + "@" + level.dimension().location();
    }

    private static String catalystFlag(ItemStack stack) {
        return CATALYST_FLAG_PREFIX + catalystFingerprint(stack);
    }

    private static List<Object> findCandidateRecipes(ServerLevel level, IPatternDetails pattern) {
        var matches = new ArrayList<Object>();
        for (var recipe : recipes(level)) {
            var output = MalumReflection.focusingOutput(recipe);
            if (output == null || output.isEmpty() || !MalumAdapterSupport.outputMatches(pattern, output)) {
                continue;
            }
            var spiritStacks = MalumReflection.focusingSpirits(recipe);
            if (MalumReflection.focusingInput(recipe) == null
                    || spiritStacks == null
                    || MalumAdapterSupport.requirementsFromStacks(spiritStacks) == null) {
                continue;
            }
            matches.add(recipe);
        }
        return List.copyOf(matches);
    }

    private static BiFunction<GenericStack, Actionable, Long> spiritInserter(
            ServerLevel level,
            BlockPos mainPos,
            Ingredient catalystIngredient,
            int slot,
            MalumRecipeInputMatcher.Assignment<AEItemKey> assignment) {
        return (stack, mode) -> {
            if (!MalumAdapterSupport.matchesPlannedStack(stack, assignment)) {
                return 0L;
            }

            var be = level.getBlockEntity(mainPos);
            if (be == null
                    || !blockId(be.getBlockState()).equals(CRUCIBLE_BLOCK)
                    || !MalumReflection.isSpiritCrucible(be)
                    || !MalumReflection.isCrucibleInactive(be)
                    || !hasCrucibleComponent(level, mainPos)) {
                return 0L;
            }

            var catalystInventory = MalumReflection.crucibleInventory(be);
            var spiritInventory = MalumReflection.crucibleSpiritInventory(be);
            if (catalystInventory == null || spiritInventory == null || catalystInventory.getSlots() < 1) {
                return 0L;
            }

            var planned = MalumAdapterSupport.toItemStack(assignment);
            if (!MalumAdapterSupport.canPlace(spiritInventory, slot, planned)) {
                return 0L;
            }

            if (mode == Actionable.MODULATE) {
                var catalyst = catalystInventory.getStackInSlot(0);
                if (catalyst.isEmpty() || !catalystIngredient.test(catalyst)) {
                    return 0L;
                }
                long inserted = 0L;
                for (long i = 0; i < assignment.amount(); i++) {
                    if (!MalumReflection.insertItem(
                            level, spiritInventory, slot, assignment.value().toStack(1))) {
                        break;
                    }
                    inserted++;
                }
                return inserted;
            }
            return assignment.amount();
        };
    }

    private static BiFunction<GenericStack, Actionable, Long> catalystInserter(
            ServerLevel level,
            BlockPos mainPos,
            Ingredient catalystIngredient,
            MalumRecipeInputMatcher.Assignment<AEItemKey> assignment) {
        return (stack, mode) -> {
            if (!MalumAdapterSupport.matchesPlannedStack(stack, assignment)) {
                return 0L;
            }

            var be = level.getBlockEntity(mainPos);
            if (be == null
                    || !blockId(be.getBlockState()).equals(CRUCIBLE_BLOCK)
                    || !MalumReflection.isSpiritCrucible(be)
                    || !MalumReflection.isCrucibleInactive(be)
                    || !hasCrucibleComponent(level, mainPos)) {
                return 0L;
            }

            var catalystInventory = MalumReflection.crucibleInventory(be);
            if (catalystInventory == null || catalystInventory.getSlots() < 1) {
                return 0L;
            }

            var planned = MalumAdapterSupport.toItemStack(assignment);
            if (!catalystIngredient.test(planned)
                    || !MalumAdapterSupport.canPlace(catalystInventory, 0, planned)) {
                return 0L;
            }

            if (mode == Actionable.MODULATE) {
                if (!MalumReflection.insertItem(level, catalystInventory, 0, planned)) {
                    return 0L;
                }
            }
            return assignment.amount();
        };
    }

    private static boolean hasCrucibleComponent(ServerLevel level, BlockPos mainPos) {
        var componentPos = mainPos.above();
        return level.isLoaded(componentPos)
                && blockId(level.getBlockState(componentPos)).equals(CRUCIBLE_COMPONENT_BLOCK);
    }

    private static AABB crucibleOutputAabb(BlockPos pos) {
        return new AABB(
                pos.getX() - 0.25,
                pos.getY() + 1.0,
                pos.getZ() - 0.25,
                pos.getX() + 1.25,
                pos.getY() + 2.5,
                pos.getZ() + 1.25);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static List<Recipe<?>> recipes(ServerLevel level) {
        var type = AdapterRecipeTypes.find(RECIPE_TYPE_ID);
        if (type == null) {
            return List.of();
        }
        return (List<Recipe<?>>) (List<?>) level.getRecipeManager()
                .getAllRecipesFor((RecipeType) type);
    }

    private static boolean isMalumLoaded() {
        try {
            return ModList.get().isLoaded(MOD_ID);
        } catch (RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    private static ResourceLocation blockId(BlockState state) { return AdapterBlocks.idOf(state); }

    private static ResourceLocation malumId(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }

    private static boolean hasSingleItemOutput(IPatternDetails pattern) {
        var outputs = pattern.getOutputs();
        return outputs.length == 1 && outputs[0].what() instanceof AEItemKey;
    }

    private record RecipeMatch(
            ResourceLocation recipeId,
            Ingredient catalystIngredient,
            MalumRecipeInputMatcher.Assignment<AEItemKey> catalyst,
            List<MalumRecipeInputMatcher.Assignment<AEItemKey>> spirits,
            ItemStack output) {
        RecipeMatch {
            Objects.requireNonNull(recipeId, "recipeId");
            Objects.requireNonNull(catalystIngredient, "catalystIngredient");
            Objects.requireNonNull(catalyst, "catalyst");
            spirits = List.copyOf(spirits);
            output = output.copy();
        }
    }

    record FocusInputMatch<T>(
            MalumRecipeInputMatcher.Assignment<T> catalyst,
            List<MalumRecipeInputMatcher.Assignment<T>> spirits) {
        FocusInputMatch {
            Objects.requireNonNull(catalyst, "catalyst");
            spirits = List.copyOf(spirits);
        }
    }

    /** Opaque binding handle returned from {@link #bind}. */
    private record FocusingBindHandle(List<Object> recipes) {
        FocusingBindHandle {
            recipes = List.copyOf(recipes);
        }
    }
}
