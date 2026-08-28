package com.moakiee.ae2lt.packaged.logic.multiblock.occultism;

import java.lang.reflect.Constructor;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.fml.ModList;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;

import com.moakiee.ae2lt.packaged.patternprovider.AllowedOutputFilter;
import com.moakiee.ae2lt.packaged.patternprovider.OverloadPatternSemantics;
import com.moakiee.ae2lt.packaged.logic.multiblock.DispatchPlan;
import com.moakiee.ae2lt.packaged.logic.multiblock.AdapterBlocks;
import com.moakiee.ae2lt.packaged.logic.multiblock.VirtualCraftingAdapter;
import com.moakiee.ae2lt.packaged.logic.multiblock.VirtualCraftingResult;
import com.moakiee.ae2lt.packaged.logic.multiblock.binding.BindingMode;
import com.moakiee.ae2lt.packaged.logic.multiblock.binding.BindingResult;

/**
 * Runtime adapter for Occultism's Spirit Fire item-conversion blocks.
 *
 * <p>Spirit Fire is the "throw item into colored fire to convert it" mechanic
 * (block: {@code occultism:spirit_fire}, recipe class: {@code SpiritFireRecipe}
 * &mdash; a {@code Recipe<ItemStackFakeInventory>}). Players normally drop
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
        if (!(bind.recipe() instanceof Recipe<?> recipe)) {
            return null;
        }

        var input = singleInput(inputs);
        if (input == null) {
            return null;
        }

        // SpiritFireRecipe is Recipe<ItemStackFakeInventory>: its erased
        // matches()/assemble() cast the argument, so a plain SimpleContainer
        // would throw ClassCastException inside every call. The fake input
        // stack carries the full per-craft amount — in-world conversion
        // yields output count equal to the dropped stack's count
        // (SpiritFireRecipe#assemble: result.setCount(inv.input.getCount())).
        long perCraftInput = perCraftInputAmount(pattern);
        if (perCraftInput <= 0 || perCraftInput > Integer.MAX_VALUE
                || input.amount() % perCraftInput != 0) {
            return null;
        }
        long craftRatio = input.amount() / perCraftInput;

        var recipeInput = FakeInventoryReflection.createInput(
                input.key(), (int) perCraftInput);
        if (recipeInput == null) {
            return null;
        }

        if (!matchesSafe(recipe, recipeInput, level)) {
            return null;
        }
        var result = assembleSafe(recipe, recipeInput, level);
        if (result == null || result.isEmpty()) {
            return null;
        }

        // Parallel batch accounting: craftRatio = input.amount / perCraftInput,
        // totalCount = craftRatio × recipe output count. The pattern's
        // declared output must match exactly (any N:N pattern shape works).
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
    private static Recipe<?> findCandidateRecipe(ServerLevel level, IPatternDetails pattern) {
        for (var recipe : recipes(level)) {
            ItemStack result;
            try {
                result = recipe.getResultItem(level.registryAccess());
            } catch (RuntimeException | LinkageError ignored) {
                continue;
            }
            if (result == null || result.isEmpty() || !outputKeyMatchesByItem(pattern, result)) {
                continue;
            }
            return recipe;
        }
        return null;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static boolean matchesSafe(net.minecraft.world.item.crafting.Recipe<?> recipe,
                                       Container input, ServerLevel level) {
        try {
            return ((net.minecraft.world.item.crafting.Recipe) recipe).matches(input, level);
        } catch (RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    @Nullable
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static ItemStack assembleSafe(net.minecraft.world.item.crafting.Recipe<?> recipe,
                                          Container input, ServerLevel level) {
        try {
            return ((net.minecraft.world.item.crafting.Recipe) recipe)
                    .assemble(input, level.registryAccess())
                    .copy();
        } catch (RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static List<Recipe<?>> recipes(ServerLevel level) {
        return BuiltInRegistries.RECIPE_TYPE.getOptional(SPIRIT_FIRE_RECIPE_TYPE)
                .map(type -> (List<Recipe<?>>) (List<?>) level.getRecipeManager()
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
        return outputs.length == 1 && outputs[0].what() instanceof AEItemKey;
    }

    /**
     * Bind-time pre-filter: pattern output key must point to the same item
     * the recipe assembles, ignoring amount entirely. Amount-side validation
     * is deferred to {@link #outputKeyMatchesBatch} once the batch ratio is
     * known.
     */
    private static boolean outputKeyMatchesByItem(IPatternDetails pattern, ItemStack result) {
        var outputs = pattern.getOutputs();
        if (outputs.length != 1) {
            return false;
        }
        var expected = outputs[0];
        if (!(expected.what() instanceof AEItemKey expectedKey)) {
            return false;
        }
        var actual = AEItemKey.of(result);
        return OverloadPatternSemantics.isIdOnlyOutput(pattern, 0)
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
        if (outputs.length != 1) {
            return false;
        }
        var expected = outputs[0];
        if (!(expected.what() instanceof AEItemKey expectedKey)) {
            return false;
        }
        if (expected.amount() != expectedTotalCount) {
            return false;
        }
        var actual = AEItemKey.of(result);
        return OverloadPatternSemantics.isIdOnlyOutput(pattern, 0)
                ? expectedKey.dropSecondary().equals(actual.dropSecondary())
                : expectedKey.equals(actual);
    }

    /**
     * Per-craft input amount declared by the pattern: the base encoded stack
     * amount × multiplier. Spirit Fire converts a whole stack at its count,
     * so this is also the count of the fake input stack we build.
     */
    private static long perCraftInputAmount(IPatternDetails pattern) {
        var inputs = pattern.getInputs();
        if (inputs.length > 0) {
            var possible = inputs[0].getPossibleInputs();
            long base = possible.length > 0 ? Math.max(1, possible[0].amount()) : 1L;
            long multiplier = Math.max(1L, inputs[0].getMultiplier());
            try {
                return Math.multiplyExact(base, multiplier);
            } catch (ArithmeticException ignored) {
                return -1L;
            }
        }
        return 1L;
    }

    private static boolean isOccultismLoaded() {
        return ModList.get().isLoaded(MOD_ID);
    }

    private static ResourceLocation blockId(BlockState state) { return AdapterBlocks.idOf(state); }

    private static ResourceLocation occultismId(String path) {
        return new ResourceLocation(MOD_ID, path);
    }

    /** Opaque binding handle returned from {@link #bind}; carries the matched recipe. */
    private record SpiritFireBindHandle(Object recipe) {
    }

    private record SingleInput(AEItemKey key, long amount) {
    }

    /**
     * Reflection shell for Occultism's single-slot recipe inventory
     * ({@code com.klikli_dev.occultism.crafting.recipe.ItemStackFakeInventory}).
     * Occultism is not a compile dependency.
     */
    private static final class FakeInventoryReflection {
        private static final org.slf4j.Logger LOG =
                org.slf4j.LoggerFactory.getLogger(FakeInventoryReflection.class);
        private static final String CLASS_NAME =
                "com.klikli_dev.occultism.crafting.recipe.ItemStackFakeInventory";

        private static volatile boolean lookupDone;
        private static volatile @Nullable Constructor<?> stackConstructor;

        @Nullable
        static Container createInput(AEItemKey key, int count) {
            ensureLookup();
            if (stackConstructor == null) {
                return null;
            }
            try {
                return (Container) stackConstructor.newInstance(key.toStack(count));
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                LOG.warn("[ae2ltpp] Failed to build Occultism fake inventory: {}", e.toString());
                return null;
            }
        }

        private static void ensureLookup() {
            if (lookupDone) {
                return;
            }
            synchronized (FakeInventoryReflection.class) {
                if (lookupDone) {
                    return;
                }
                try {
                    stackConstructor = Class.forName(CLASS_NAME).getConstructor(ItemStack.class);
                } catch (ClassNotFoundException | NoSuchMethodException
                        | RuntimeException | LinkageError e) {
                    LOG.warn("[ae2ltpp] Occultism ItemStackFakeInventory unavailable: {}", e.toString());
                } finally {
                    lookupDone = true;
                }
            }
        }
    }
}
