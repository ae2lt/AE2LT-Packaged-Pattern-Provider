package com.moakiee.ae2lt.packaged.logic.multiblock.malum;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.neoforged.neoforge.common.crafting.SizedIngredient;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.IItemHandlerModifiable;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;

import com.moakiee.thunderbolt.ae2.overload.model.MatchMode;
import com.moakiee.thunderbolt.ae2.overload.pattern.OverloadedProviderOnlyPatternDetails;

final class MalumAdapterSupport {

    private MalumAdapterSupport() {
    }

    @Nullable
    static List<MalumRecipeInputMatcher.Input<AEItemKey>> aggregateInputs(KeyCounter[] inputs, long maxAmount) {
        Map<AEItemKey, Long> amounts = new LinkedHashMap<>();
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
                if (total > maxAmount) {
                    return null;
                }
                amounts.merge(itemKey, amount, Long::sum);
            }
        }

        var aggregated = new ArrayList<MalumRecipeInputMatcher.Input<AEItemKey>>(amounts.size());
        for (var entry : amounts.entrySet()) {
            aggregated.add(new MalumRecipeInputMatcher.Input<>(entry.getKey(), entry.getValue()));
        }
        return List.copyOf(aggregated);
    }

    static MalumRecipeInputMatcher.Requirement<AEItemKey> requirement(SizedIngredient ingredient) {
        return requirement(ingredient.ingredient(), ingredient.count());
    }

    static MalumRecipeInputMatcher.Requirement<AEItemKey> requirement(Ingredient ingredient, long amount) {
        return new MalumRecipeInputMatcher.Requirement<>(
                amount,
                key -> ingredient.test(key.toStack(1)));
    }

    @Nullable
    static List<MalumRecipeInputMatcher.Requirement<AEItemKey>> requirementsFromStacks(List<ItemStack> stacks) {
        var requirements = new ArrayList<MalumRecipeInputMatcher.Requirement<AEItemKey>>(stacks.size());
        for (var stack : stacks) {
            if (stack.isEmpty() || stack.getCount() <= 0) {
                return null;
            }
            var expected = AEItemKey.of(stack);
            requirements.add(new MalumRecipeInputMatcher.Requirement<>(
                    stack.getCount(),
                    expected::equals));
        }
        return List.copyOf(requirements);
    }

    static boolean outputMatches(IPatternDetails pattern, ItemStack result) {
        var outputs = pattern.getOutputs();
        if (outputs.size() != 1) {
            return false;
        }

        var expected = outputs.getFirst();
        if (!(expected.what() instanceof AEItemKey expectedKey)
                || expected.amount() != result.getCount()) {
            return false;
        }

        var actual = AEItemKey.of(result);
        return outputMode(pattern, 0) == MatchMode.ID_ONLY
                ? expectedKey.dropSecondary().equals(actual.dropSecondary())
                : expectedKey.equals(actual);
    }

    static boolean inventoryEmpty(IItemHandler inventory) {
        for (int i = 0; i < inventory.getSlots(); i++) {
            if (!inventory.getStackInSlot(i).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    static boolean canPlace(IItemHandlerModifiable inventory, int slot, ItemStack stack) {
        return slot >= 0
                && slot < inventory.getSlots()
                && stack.getCount() <= inventory.getSlotLimit(slot)
                && inventory.getStackInSlot(slot).isEmpty()
                && inventory.isItemValid(slot, stack);
    }

    static boolean matchesPlannedStack(
            GenericStack stack,
            MalumRecipeInputMatcher.Assignment<AEItemKey> assignment) {
        return stack.amount() == assignment.amount() && assignment.value().equals(stack.what());
    }

    static GenericStack toGenericStack(MalumRecipeInputMatcher.Assignment<AEItemKey> assignment) {
        return new GenericStack(assignment.value(), assignment.amount());
    }

    static ItemStack toItemStack(MalumRecipeInputMatcher.Assignment<AEItemKey> assignment) {
        return assignment.value().toStack((int) assignment.amount());
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
}
