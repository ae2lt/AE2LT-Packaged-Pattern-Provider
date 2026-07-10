package com.moakiee.ae2lt.packaged.logic.multiblock.occultism;

import java.util.List;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.fml.ModList;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;

import com.moakiee.ae2lt.logic.AllowedOutputFilter;
import com.moakiee.thunderbolt.ae2.overload.model.MatchMode;
import com.moakiee.thunderbolt.ae2.overload.pattern.OverloadedProviderOnlyPatternDetails;
import com.moakiee.ae2lt.packaged.logic.multiblock.DispatchPlan;
import com.moakiee.ae2lt.packaged.logic.multiblock.VirtualCraftingAdapter;
import com.moakiee.ae2lt.packaged.logic.multiblock.VirtualCraftingResult;
import com.moakiee.ae2lt.packaged.logic.multiblock.binding.BindingMode;
import com.moakiee.ae2lt.packaged.logic.multiblock.binding.BindingResult;

/**
 * Runtime adapter for Occultism's Spirit Fire item-conversion blocks.
 *
 * <p>Spirit Fire is the "throw item into colored fire to convert it" mechanic
 * (block: {@code occultism:spirit_fire}, recipe class: {@code SpiritFireRecipe}
 * &mdash; a vanilla {@code Recipe<SingleRecipeInput>}). Players normally drop
 * an item into the fire and wait ~40 ticks for the conversion to fire-trigger.
 *
 * <p>Spirit Fire has **no fuel/energy/cooldown** — the wait is purely visual.
 * That makes it a virtual-craft candidate by the equivalence principle: the
 * packaged provider can compute the result without ever spawning a real
 * ItemEntity, and the player sees the converted product appear instantly in
 * the return inventory just as they would from picking it up off the floor.
 *
 * <p>Pure reflection: Occultism is not a compile dependency.
 */
public final class OccultismSpiritFireAdapter implements VirtualCraftingAdapter {

    private static final String MOD_ID = "occultism";
    private static final ResourceLocation SPIRIT_FIRE_BLOCK = occultismId("spirit_fire");
    private static final ResourceLocation SPIRIT_FIRE_RECIPE_TYPE = occultismId("spirit_fire");

    /**
     * Audio cue played when a virtual batch flushes. Matches what
     * {@code SpiritFireBlock.entityInside} plays after each item conversion
     * (see Occultism's source). Looked up at flush time, so if Occultism is
     * absent the provider falls back to the generic pickup chime.
     */
    private static final ResourceLocation FLUSH_SOUND_ID = occultismId("start_ritual");

    @Override
    public int priority() {
        return 100;
    }

    @Override
    public boolean recognizesMain(ServerLevel level, BlockPos pos, BlockEntity be) {
        // Spirit Fire is a BlockEntity-less block; recognize by BlockState instead.
        // recognizesMain is also called by NeighborMainBlockIndex with the live BE,
        // so we still accept a null BE here by checking the surrounding state.
        if (!isOccultismLoaded()) {
            return false;
        }
        var state = level.getBlockState(pos);
        return blockId(state).equals(SPIRIT_FIRE_BLOCK);
    }

    @Override
    public ResourceLocation requiredAdapterId(ServerLevel level, BlockPos pos) {
        return com.moakiee.ae2lt.packaged.item.AdapterIds.OCCULTISM_SPIRIT_FIRE;
    }

    @Override
    public ResourceLocation flushSoundId() {
        return FLUSH_SOUND_ID;
    }

    @Override
    @Nullable
    public BindingResult bind(ServerLevel level, BlockPos mainPos, IPatternDetails pattern) {
        var state = level.getBlockState(mainPos);
        if (!blockId(state).equals(SPIRIT_FIRE_BLOCK) || !isOccultismLoaded()) {
            return null;
        }
        if (!hasSingleItemOutput(pattern)) {
            return null;
        }
        var recipe = findCandidateRecipe(level, pattern);
        if (recipe == null) {
            return null;
        }
        return new BindingResult(new SpiritFireBindHandle(recipe), BindingMode.VIRTUAL);
    }

    @Override
    public boolean canDispatch(ServerLevel level, BlockPos mainPos, Object handle) {
        // Virtual lanes have no machine state to gate on; the block-state recheck
        // happens inside planVirtualWithBinding so a player breaking the fire
        // mid-flight is caught.
        return true;
    }

    @Override
    @Nullable
    public DispatchPlan planWithBinding(ServerLevel level, BlockPos mainPos,
                                        IPatternDetails pattern, KeyCounter[] inputs,
                                        Object handle, IActionSource source) {
        // Virtual-only.
        return null;
    }

    @Override
    @Nullable
    public VirtualCraftingResult planVirtualWithBinding(ServerLevel level, BlockPos mainPos,
                                                        IPatternDetails pattern, KeyCounter[] inputs,
                                                        Object handle, IActionSource source) {
        if (!(handle instanceof SpiritFireBindHandle bind)) {
            return null;
        }
        var state = level.getBlockState(mainPos);
        if (!blockId(state).equals(SPIRIT_FIRE_BLOCK)) {
            return null;
        }
        if (!(bind.recipe() instanceof RecipeHolder<?> holder)
                || !(holder.value() instanceof net.minecraft.world.item.crafting.Recipe<?> recipe)) {
            return null;
        }

        var input = singleInput(inputs);
        if (input == null) {
            return null;
        }

        // Build a SingleRecipeInput (the recipe class expects this exact type).
        var inputStack = input.key().toStack(1);
        SingleRecipeInput recipeInput;
        try {
            recipeInput = new SingleRecipeInput(inputStack);
        } catch (RuntimeException | LinkageError ignored) {
            return null;
        }

        if (!matchesSafe(recipe, recipeInput, level)) {
            return null;
        }
        var result = assembleSafe(recipe, recipeInput, level);
        if (result == null || result.isEmpty()) {
            return null;
        }

        // Parallel batch accounting.
        //
        // Concept: one pushPattern represents the entire batch the user
        // encoded into the AE pattern (e.g. "8 coal → 8 enori"), NOT a
        // single recipe assemble. We compute the batch ratio from the
        // *input* side first, then derive the expected total output, and
        // only accept the recipe if the pattern's declared output exactly
        // matches that derived total. This lets the user write any N:N
        // pattern shape and still get the right output, instead of being
        // forced into a strict 1:1 template.
        //
        //   craftRatio  = input.amount / perCraftInput
        //   totalCount  = craftRatio * recipe.result.count
        //   require        pattern.output.amount == totalCount
        long perCraftInput = perCraftInputAmount(pattern);
        if (perCraftInput <= 0 || input.amount() % perCraftInput != 0) {
            return null;
        }
        long craftRatio = input.amount() / perCraftInput;
        long totalCount;
        try {
            totalCount = Math.multiplyExact(craftRatio, (long) result.getCount());
        } catch (ArithmeticException ignored) {
            return null;
        }
        if (totalCount <= 0 || totalCount > Integer.MAX_VALUE) {
            return null;
        }

        if (!outputKeyMatchesBatch(pattern, result, totalCount)) {
            return null;
        }

        return new VirtualCraftingResult(List.of(
                new GenericStack(AEItemKey.of(result), totalCount)));
    }

    @Override
    public List<GenericStack> extractOutputs(ServerLevel level, BlockPos mainPos,
                                             AllowedOutputFilter filter,
                                             IActionSource source) {
        // No physical output to pull back: virtual lanes deliver straight to
        // the return inventory. The 40-tick in-world conversion never starts.
        return List.of();
    }

    // ===== Recipe search =====

    /**
     * Bind-time scan: pick the recipe whose result <i>item</i> matches the
     * pattern's primary output. Amount is intentionally not checked here —
     * the pattern's amount carries the batch size, which is rechecked at
     * plan time once we know the runtime input ratio.
     */
    @Nullable
    private static RecipeHolder<?> findCandidateRecipe(ServerLevel level, IPatternDetails pattern) {
        for (var holder : recipes(level)) {
            var recipe = holder.value();
            ItemStack result;
            try {
                result = recipe.getResultItem(level.registryAccess());
            } catch (RuntimeException | LinkageError ignored) {
                continue;
            }
            if (result == null || result.isEmpty() || !outputKeyMatchesByItem(pattern, result)) {
                continue;
            }
            return holder;
        }
        return null;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static boolean matchesSafe(net.minecraft.world.item.crafting.Recipe<?> recipe,
                                       SingleRecipeInput input, ServerLevel level) {
        try {
            return ((net.minecraft.world.item.crafting.Recipe) recipe).matches(input, level);
        } catch (RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    @Nullable
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static ItemStack assembleSafe(net.minecraft.world.item.crafting.Recipe<?> recipe,
                                          SingleRecipeInput input, ServerLevel level) {
        try {
            return ((net.minecraft.world.item.crafting.Recipe) recipe)
                    .assemble(input, level.registryAccess())
                    .copy();
        } catch (RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static List<RecipeHolder<?>> recipes(ServerLevel level) {
        return BuiltInRegistries.RECIPE_TYPE.getOptional(SPIRIT_FIRE_RECIPE_TYPE)
                .map(type -> (List<RecipeHolder<?>>) (List<?>) level.getRecipeManager()
                        .getAllRecipesFor((RecipeType) type))
                .orElse(List.of());
    }

    // ===== Helpers =====

    @Nullable
    private static SingleInput singleInput(KeyCounter[] inputs) {
        AEItemKey key = null;
        long amount = 0;
        for (var counter : inputs) {
            for (var entry : counter) {
                if (!(entry.getKey() instanceof AEItemKey itemKey)) {
                    return null;
                }
                long entryAmount = entry.getLongValue();
                if (entryAmount <= 0) {
                    continue;
                }
                if (key == null) {
                    key = itemKey;
                } else if (!key.equals(itemKey)) {
                    return null;
                }
                amount += entryAmount;
            }
        }
        return (key != null && amount > 0) ? new SingleInput(key, amount) : null;
    }

    private static boolean hasSingleItemOutput(IPatternDetails pattern) {
        var outputs = pattern.getOutputs();
        return outputs.size() == 1 && outputs.getFirst().what() instanceof AEItemKey;
    }

    /**
     * Bind-time pre-filter: pattern output key must point to the same item
     * the recipe assembles, ignoring amount entirely. Amount-side validation
     * is deferred to {@link #outputKeyMatchesBatch} once the batch ratio is
     * known.
     */
    private static boolean outputKeyMatchesByItem(IPatternDetails pattern, ItemStack result) {
        var outputs = pattern.getOutputs();
        if (outputs.size() != 1) {
            return false;
        }
        var expected = outputs.getFirst();
        if (!(expected.what() instanceof AEItemKey expectedKey)) {
            return false;
        }
        var actual = AEItemKey.of(result);
        return outputMode(pattern, 0) == MatchMode.ID_ONLY
                ? expectedKey.dropSecondary().equals(actual.dropSecondary())
                : expectedKey.equals(actual);
    }

    /**
     * Plan-time strict validation: the pattern's declared output amount must
     * equal {@code craftRatio × recipe.result.count}, where craftRatio comes
     * from the input side. This is what lets batched patterns
     * (e.g. {@code 8 coal → 8 enori}) be accepted in one shot — the per-craft
     * 1:1 check that we used previously rejected anything but
     * {@code 1 → 1} templates.
     */
    private static boolean outputKeyMatchesBatch(IPatternDetails pattern, ItemStack result,
                                                  long expectedTotalCount) {
        var outputs = pattern.getOutputs();
        if (outputs.size() != 1) {
            return false;
        }
        var expected = outputs.getFirst();
        if (!(expected.what() instanceof AEItemKey expectedKey)) {
            return false;
        }
        if (expected.amount() != expectedTotalCount) {
            return false;
        }
        var actual = AEItemKey.of(result);
        return outputMode(pattern, 0) == MatchMode.ID_ONLY
                ? expectedKey.dropSecondary().equals(actual.dropSecondary())
                : expectedKey.equals(actual);
    }

    /**
     * Look up the per-craft input amount declared by the pattern. AE2's
     * vanilla {@code IPatternDetails} doesn't expose this directly; the
     * Overloaded pattern model does, so we reach in via that interface.
     * Defaults to 1, matching Spirit Fire's intrinsic 1-input shape.
     */
    private static long perCraftInputAmount(IPatternDetails pattern) {
        if (pattern instanceof OverloadedProviderOnlyPatternDetails overload) {
            var overloadInputs = overload.overloadPatternDetailsView().inputs();
            if (!overloadInputs.isEmpty()) {
                return Math.max(1, overloadInputs.getFirst().amountPerCraft());
            }
        }
        return 1L;
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

    private static boolean isOccultismLoaded() {
        return ModList.get().isLoaded(MOD_ID);
    }

    private static ResourceLocation blockId(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock());
    }

    private static ResourceLocation occultismId(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }

    /** Opaque binding handle returned from {@link #bind}; carries the matched recipe. */
    private record SpiritFireBindHandle(Object recipe) {
    }

    private record SingleInput(AEItemKey key, long amount) {
    }
}
