package com.moakiee.ae2lt.packaged.logic.multiblock.malum;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
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
import com.moakiee.ae2lt.packaged.logic.multiblock.DispatchPlan;
import com.moakiee.ae2lt.packaged.logic.multiblock.DroppedItemDispatch;
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
        if (!(handle instanceof FocusingBindHandle bind)) {
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
                () -> MalumReflection.updateCrucibleRecipe(level, mainPos));
    }

    @Override
    public List<GenericStack> extractOutputs(ServerLevel level, BlockPos mainPos,
                                             AllowedOutputFilter filter,
                                             IActionSource source) {
        var be = level.getBlockEntity(mainPos);
        if (be == null || !recognizesMain(level, mainPos, be)) {
            return List.of();
        }
        var outputs = new ArrayList<>(DroppedItemDispatch.collectOutputs(
                level,
                crucibleOutputAabb(mainPos),
                filter,
                entity -> true));
        var catalyst = extractReusableCatalyst(be);
        if (!catalyst.isEmpty()) {
            outputs.add(new GenericStack(AEItemKey.of(catalyst), catalyst.getCount()));
        }
        return List.copyOf(outputs);
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
            if (match != null) {
                return new RecipeMatch(input, match.catalyst(), match.spirits());
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

    private static ItemStack extractReusableCatalyst(BlockEntity be) {
        if (!MalumReflection.isCrucibleInactive(be)) {
            return ItemStack.EMPTY;
        }
        var inventory = MalumReflection.crucibleInventory(be);
        if (inventory == null || inventory.getSlots() < 1) {
            return ItemStack.EMPTY;
        }
        var stack = inventory.getStackInSlot(0);
        if (stack.isEmpty()) {
            return ItemStack.EMPTY;
        }
        return inventory.extractItem(0, stack.getCount(), false);
    }

    private static List<Object> findCandidateRecipes(ServerLevel level, IPatternDetails pattern) {
        var matches = new ArrayList<Object>();
        for (var holder : recipes(level)) {
            var recipe = holder;
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
                if (!MalumReflection.insertItem(level, spiritInventory, slot, planned)) {
                    return 0L;
                }
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
        return BuiltInRegistries.RECIPE_TYPE.getOptional(RECIPE_TYPE_ID)
                .map(type -> (List<Recipe<?>>) (List<?>) level.getRecipeManager()
                        .getAllRecipesFor((RecipeType) type))
                .orElse(List.of());
    }

    private static boolean isMalumLoaded() {
        try {
            return ModList.get().isLoaded(MOD_ID);
        } catch (RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    private static ResourceLocation blockId(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock());
    }

    private static ResourceLocation malumId(String path) {
        return new ResourceLocation(MOD_ID, path);
    }

    private static boolean hasSingleItemOutput(IPatternDetails pattern) {
        var outputs = pattern.getOutputs();
        return outputs.length == 1 && outputs[0].what() instanceof AEItemKey;
    }

    private record RecipeMatch(
            Ingredient catalystIngredient,
            MalumRecipeInputMatcher.Assignment<AEItemKey> catalyst,
            List<MalumRecipeInputMatcher.Assignment<AEItemKey>> spirits) {
        RecipeMatch {
            Objects.requireNonNull(catalystIngredient, "catalystIngredient");
            Objects.requireNonNull(catalyst, "catalyst");
            spirits = List.copyOf(spirits);
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
