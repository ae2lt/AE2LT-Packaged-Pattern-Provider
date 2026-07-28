package com.moakiee.ae2lt.packaged.logic.multiblock.botania;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
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
 * Virtual adapter for the Alfheim Portal &mdash; the Elven Trade
 * multi-block where mundane items are exchanged for elven equivalents.
 * Player workflow: throw input stacks into the active (ON_X/ON_Z)
 * portal, the portal spawns the traded outputs after a short delay.
 *
 * <p>Equivalence model: we accept the trade only when the portal is
 * physically open (ticks-open counter positive or controller state in
 * ON_X/ON_Z), then validate the runtime {@link KeyCounter} against the
 * recipe's ingredients with NBT-strict {@code ingredient.test}, and
 * return the recipe's declared {@code getOutputs()} list verbatim.
 *
 * <p>Batch policy: K=1 (single trade per push). Elven Trade outputs
 * are typically multi-stack and arbitrary item; deriving a robust
 * craftRatio from {@code pattern.outputs} divided by recipe outputs is
 * ambiguous when outputs contain multiple items, so we keep batching
 * off and let the user submit multiple patterns for volume.
 *
 * <p>Anti-duplication path:
 * <ul>
 *   <li>bind verifies that the pattern's outputs <i>exactly</i> match
 *       the recipe's {@code getOutputs()} (one-to-one item + amount,
 *       NBT-strict under STRICT, item-id-only under ID_ONLY). No
 *       extras and nothing missing.</li>
 *   <li>plan re-runs strict {@code ingredient.test} on each KeyCounter
 *       key &mdash; under ID_ONLY a different NBT variant cannot
 *       satisfy the ingredient.</li>
 *   <li>Returned stacks are {@code AEItemKey.of(output)} from the
 *       recipe, never from the pattern.</li>
 * </ul>
 *
 * <p>No side effect: the portal's mana/ticks are left untouched so the
 * player's own automation (pylons, sparks) continues to govern portal
 * lifetime. A closed portal simply causes {@link #canDispatch} to
 * return false and the push falls through to other lanes.
 */
public final class AlfheimPortalAdapter implements VirtualCraftingAdapter {

    @Override
    public int priority() {
        return 100;
    }

    @Override
    public boolean recognizesMain(ServerLevel level, BlockPos pos, @Nullable BlockEntity be) {
        return BotaniaReflection.isAlfheimPortal(be);
    }

    @Override
    public ResourceLocation requiredAdapterId(ServerLevel level, BlockPos pos) {
        return AdapterIds.BOTANIA_ALFHEIM_PORTAL;
    }

    @Override
    @Nullable
    public BindingResult bind(ServerLevel level, BlockPos mainPos, IPatternDetails pattern) {
        var be = level.getBlockEntity(mainPos);
        if (!recognizesMain(level, mainPos, be)) {
            return null;
        }
        var holder = BotaniaRecipeLookup.findElvenTradeByOutputs(level, pattern);
        if (holder == null) {
            return null;
        }
        return new BindingResult(new PortalBindHandle(holder), BindingMode.VIRTUAL);
    }

    @Override
    public boolean canDispatch(ServerLevel level, BlockPos mainPos, Object handle) {
        var be = level.getBlockEntity(mainPos);
        return BotaniaReflection.isAlfheimPortal(be)
                && BotaniaReflection.portalIsOpen(be, level.getBlockState(mainPos));
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
        if (!(handle instanceof PortalBindHandle bind)) {
            return null;
        }
        var be = level.getBlockEntity(mainPos);
        if (!recognizesMain(level, mainPos, be)
                || !BotaniaReflection.portalIsOpen(be, level.getBlockState(mainPos))) {
            return null;
        }

        var recipe = bind.holder().value();
        var ingredients = recipe.getIngredients();
        if (!consumeIngredientsStrict(ingredients, inputs)) {
            return null;
        }

        var outputs = BotaniaRecipeLookup.elvenTradeOutputs(recipe);
        if (outputs.isEmpty()) {
            return null;
        }
        if (!BotaniaRecipeLookup.validatePatternMultiOutput(pattern, outputs)) {
            return null;
        }

        var stacks = new ArrayList<GenericStack>(outputs.size());
        for (var out : outputs) {
            if (out == null || out.isEmpty()) {
                continue;
            }
            stacks.add(new GenericStack(AEItemKey.of(out), out.getCount()));
        }
        if (stacks.isEmpty()) {
            return null;
        }
        return new VirtualCraftingResult(stacks);
    }

    @Override
    public List<GenericStack> extractOutputs(ServerLevel level, BlockPos mainPos,
                                             AllowedOutputFilter filter, IActionSource source) {
        return List.of();
    }

    private static boolean consumeIngredientsStrict(List<Ingredient> ingredients, KeyCounter[] inputs) {
        var available = new LinkedHashMap<Item, Long>();
        var keysByItem = new HashMap<Item, AEItemKey>();
        for (var counter : inputs) {
            for (var entry : counter) {
                if (!(entry.getKey() instanceof AEItemKey itemKey)) {
                    return false;
                }
                long amt = entry.getLongValue();
                if (amt <= 0) {
                    continue;
                }
                var item = itemKey.getItem();
                available.merge(item, amt, Long::sum);
                keysByItem.putIfAbsent(item, itemKey);
            }
        }
        if (available.isEmpty()) {
            return false;
        }
        for (var ing : ingredients) {
            if (ing.isEmpty()) {
                continue;
            }
            boolean matched = false;
            for (var it = available.entrySet().iterator(); it.hasNext(); ) {
                var entry = it.next();
                if (entry.getValue() < 1L) {
                    continue;
                }
                var key = keysByItem.get(entry.getKey());
                if (key == null) {
                    continue;
                }
                if (!ing.test(key.toStack(1))) {
                    continue;
                }
                long remaining = entry.getValue() - 1L;
                if (remaining == 0) {
                    it.remove();
                } else {
                    entry.setValue(remaining);
                }
                matched = true;
                break;
            }
            if (!matched) {
                return false;
            }
        }
        for (var v : available.values()) {
            if (v > 0) {
                return false;
            }
        }
        return true;
    }

    private record PortalBindHandle(RecipeHolder<?> holder) {
    }
}
