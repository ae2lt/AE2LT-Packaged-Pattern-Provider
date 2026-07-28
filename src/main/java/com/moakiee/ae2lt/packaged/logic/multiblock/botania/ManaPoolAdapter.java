package com.moakiee.ae2lt.packaged.logic.multiblock.botania;

import java.util.List;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;

import com.moakiee.ae2lt.packaged.patternprovider.AllowedOutputFilter;
import com.moakiee.ae2lt.packaged.item.AdapterIds;
import com.moakiee.ae2lt.packaged.logic.multiblock.DispatchPlan;
import com.moakiee.ae2lt.packaged.logic.multiblock.VirtualCraftingAdapter;
import com.moakiee.ae2lt.packaged.logic.multiblock.VirtualCraftingResult;
import com.moakiee.ae2lt.packaged.logic.multiblock.binding.BindingMode;
import com.moakiee.ae2lt.packaged.logic.multiblock.binding.BindingResult;

/**
 * Virtual adapter for the Mana Pool's <i>Mana Infusion</i> recipes.
 *
 * <p>Mana Pool infusion converts a single input stack into a single
 * output stack and consumes a fixed amount of mana per craft. Some
 * recipes require a catalyst block under the pool (e.g. Alchemy or
 * Conjuration Catalyst), and Botania's own
 * {@code ManaPoolBlockEntity#getMatchingRecipe(ItemStack, BlockState)}
 * picks <i>catalyst-required</i> recipes over non-catalyst ones when
 * the catalyst block is present (and falls back the other way when it
 * is not).
 *
 * <p><b>Critical design note:</b> bind does <i>not</i> pin a specific
 * recipe id. Two recipes can share the same item-stack output (e.g.
 * <code>wheat → bread</code> with and without an Alchemy Catalyst); the
 * one that fires depends on the catalyst block under the pool, which
 * can change between bind and dispatch. Bind only verifies that
 * <i>some</i> mana-infusion recipe produces the pattern's declared
 * output key. The live recipe is decided at plan time via Botania's
 * own matcher, and we validate the pattern key against whichever
 * recipe Botania picks &mdash; preserving anti-dup while letting users
 * use a single pattern for both catalysed and non-catalysed flavours.
 *
 * <p>Anti-duplication rules:
 * <ul>
 *   <li>bind verifies that at least one mana-infusion recipe's
 *       {@code getResultItem(...)} key equals the pattern's declared
 *       output (NBT-strict under STRICT, item-id-only under ID_ONLY).
 *       </li>
 *   <li>plan re-runs {@code BE.getMatchingRecipe} against the runtime
 *       KeyCounter stack + the live block under the pool. The picked
 *       recipe's {@code matches(ItemStack)} is NBT-aware, so a
 *       NBT-bearing variant that bypassed bind under ID_ONLY still has
 *       to satisfy it or the dispatch is refused.</li>
 *   <li>The returned stack key + total amount are checked against the
 *       pattern by {@link BotaniaRecipeLookup#validatePatternBatchOutput}
 *       (STRICT NBT or dropSecondary under ID_ONLY).</li>
 *   <li>The returned stack is {@code AEItemKey.of(recipe.result)},
 *       never the pattern's declared key.</li>
 * </ul>
 *
 * <p>Side effect (committed at plan time, mirroring
 * ActuallyAdditions reconstructor convention): {@code receiveMana(-cost)}
 * is applied to the pool. {@code onVirtualBatchFlush} fires Botania's own
 * {@code craftingEffect(true)} which plays {@code botania:manaPoolCraft}
 * + 25 sparkle particles at the pool, throttled by the BE's internal
 * {@code soundTicks=6} cooldown. No {@code flushSoundId} is set: a
 * second cue at the provider would clash with the pool's own audio.
 */
public final class ManaPoolAdapter implements VirtualCraftingAdapter {

    @Override
    public int priority() {
        return 100;
    }

    @Override
    public boolean recognizesMain(ServerLevel level, BlockPos pos, @Nullable BlockEntity be) {
        return BotaniaReflection.isManaPool(be);
    }

    @Override
    public ResourceLocation requiredAdapterId(ServerLevel level, BlockPos pos) {
        return AdapterIds.BOTANIA_MANA_POOL;
    }

    // Note: flushSoundId() intentionally not overridden. onVirtualBatchFlush
    // calls Botania's ManaPoolBlockEntity#craftingEffect(true) which already
    // plays the canonical botania:manaPoolCraft sound (with the BE's own
    // soundTicks=6 throttle). Layering a botania:ding cue at the provider
    // on top would create a two-source dissonance for every flush.

    @Override
    @Nullable
    public BindingResult bind(ServerLevel level, BlockPos mainPos, IPatternDetails pattern) {
        var be = level.getBlockEntity(mainPos);
        if (!recognizesMain(level, mainPos, be)) {
            return null;
        }
        // Existence check only: confirm at least one mana-infusion recipe
        // produces this pattern's output. The actual recipe used at
        // dispatch time depends on the catalyst block under the pool and
        // is resolved per-craft by Botania's own matcher.
        var holder = BotaniaRecipeLookup.findManaInfusionByOutput(level, pattern);
        if (holder == null) {
            return null;
        }
        return new BindingResult(new ManaPoolBindHandle(), BindingMode.VIRTUAL);
    }

    @Override
    public boolean canDispatch(ServerLevel level, BlockPos mainPos, Object handle) {
        var be = level.getBlockEntity(mainPos);
        if (!(handle instanceof ManaPoolBindHandle) || !BotaniaReflection.isManaPool(be)) {
            return false;
        }
        // We don't know the live recipe (or its cost) at this stage —
        // catalyst presence decides which recipe fires. Use a coarse
        // liveness gate; planVirtualWithBinding does the exact check.
        return BotaniaReflection.manaPoolCurrentMana(be) > 0;
    }

    @Override
    @Nullable
    public DispatchPlan planWithBinding(ServerLevel level, BlockPos mainPos,
                                        IPatternDetails pattern, KeyCounter[] inputs,
                                        Object handle, IActionSource source) {
        return null;
    }

    @Override
    @Nullable
    public VirtualCraftingResult planVirtualWithBinding(ServerLevel level, BlockPos mainPos,
                                                        IPatternDetails pattern, KeyCounter[] inputs,
                                                        Object handle, IActionSource source) {
        if (!(handle instanceof ManaPoolBindHandle)) {
            return null;
        }
        var be = level.getBlockEntity(mainPos);
        if (!recognizesMain(level, mainPos, be)) {
            return null;
        }
        var input = singleInput(inputs);
        if (input == null) {
            return null;
        }

        // Ask Botania for the live recipe given the runtime input + the
        // block under the pool. This is the *only* place that decides
        // which of multiple same-output recipes fires (catalysed vs
        // non-catalysed). The picked recipe's NBT-aware
        // ManaInfusionRecipe.matches(stack) is also re-asserted here, so
        // a NBT-bearing variant that bypassed bind under ID_ONLY is
        // refused unless it really matches the recipe ingredient.
        var below = level.getBlockState(mainPos.below());
        var runtimeStack = input.key().toStack(1);
        var liveHolder = BotaniaReflection.manaPoolMatchingRecipe(be, runtimeStack, below);
        if (liveHolder == null) {
            return null;
        }
        var recipe = liveHolder.value();
        var result = recipe.getResultItem(level.registryAccess());
        if (result == null || result.isEmpty()) {
            return null;
        }

        long perCraftInput = 1L; // mana infusion is 1 input -> 1 output per craft
        if (input.amount() % perCraftInput != 0) {
            return null;
        }
        long craftRatio = input.amount() / perCraftInput;
        long totalCount;
        long totalManaCost;
        try {
            totalCount = Math.multiplyExact(craftRatio, (long) result.getCount());
            totalManaCost = Math.multiplyExact(craftRatio,
                    (long) BotaniaRecipeLookup.manaInfusionCost(recipe));
        } catch (ArithmeticException ignored) {
            return null;
        }
        if (totalCount <= 0 || totalCount > Integer.MAX_VALUE
                || totalManaCost < 0 || totalManaCost > Integer.MAX_VALUE) {
            return null;
        }
        if (BotaniaReflection.manaPoolCurrentMana(be) < totalManaCost) {
            return null;
        }
        if (!BotaniaRecipeLookup.validatePatternBatchOutput(pattern, result, totalCount)) {
            return null;
        }

        // Commit the side effect: drain mana for the entire batch up
        // front. Mirrors AA Reconstructor's pattern — plan-time commit
        // accepts the tiny race window of "lane acquire failure" in
        // exchange for not needing to thread side-data through the
        // flush hook.
        BotaniaReflection.manaPoolReceiveMana(be, (int) -totalManaCost);

        return new VirtualCraftingResult(List.of(
                new GenericStack(AEItemKey.of(result), totalCount)));
    }

    @Override
    public void onVirtualBatchFlush(ServerLevel level, BlockPos mainPos, Object handle, IActionSource source) {
        var be = level.getBlockEntity(mainPos);
        if (BotaniaReflection.isManaPool(be)) {
            BotaniaReflection.manaPoolCraftingEffect(be);
        }
    }

    @Override
    public List<GenericStack> extractOutputs(ServerLevel level, BlockPos mainPos,
                                             AllowedOutputFilter filter, IActionSource source) {
        return List.of();
    }

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

    private record SingleInput(AEItemKey key, long amount) {
    }

    /**
     * Marker handle: contains no recipe id because the live recipe is
     * resolved at plan time (depends on catalyst block under the pool).
     */
    private record ManaPoolBindHandle() {
    }
}
