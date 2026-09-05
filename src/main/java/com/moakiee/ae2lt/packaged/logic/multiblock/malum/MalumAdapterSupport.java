package com.moakiee.ae2lt.packaged.logic.multiblock.malum;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.IItemHandlerModifiable;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;

import com.moakiee.ae2lt.packaged.patternprovider.OverloadPatternSemantics;

final class MalumAdapterSupport {

    private MalumAdapterSupport() {
    }

    @Nullable
    static List<MalumRecipeInputMatcher.Input<AEItemKey>> aggregateInputs(KeyCounter[] inputs, long maxAmount) {
        if (inputs == null || maxAmount <= 0) {
            return null;
        }
        Map<AEItemKey, Long> amounts = new LinkedHashMap<>();
        long total = 0;
        for (var counter : inputs) {
            if (counter == null) {
                continue;
            }
            for (var entry : counter) {
                if (!(entry.getKey() instanceof AEItemKey itemKey)) {
                    return null;
                }

                long amount = entry.getLongValue();
                if (amount <= 0) {
                    continue;
                }

                try {
                    total = Math.addExact(total, amount);
                } catch (ArithmeticException overflow) {
                    return null;
                }
                if (total > maxAmount) {
                    return null;
                }
                var previous = amounts.get(itemKey);
                try {
                    amounts.put(itemKey, previous == null
                            ? amount
                            : Math.addExact(previous, amount));
                } catch (ArithmeticException overflow) {
                    return null;
                }
            }
        }

        var aggregated = new ArrayList<MalumRecipeInputMatcher.Input<AEItemKey>>(amounts.size());
        for (var entry : amounts.entrySet()) {
            aggregated.add(new MalumRecipeInputMatcher.Input<>(entry.getKey(), entry.getValue()));
        }
        return List.copyOf(aggregated);
    }

    static MalumRecipeInputMatcher.Requirement<AEItemKey> requirement(SizedIngredientView ingredient) {
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
        if (outputs.length != 1) {
            return false;
        }

        var expected = outputs[0];
        if (!(expected.what() instanceof AEItemKey expectedKey)
                || expected.amount() != result.getCount()) {
            return false;
        }

        var actual = AEItemKey.of(result);
        return OverloadPatternSemantics.isIdOnlyOutput(pattern, 0)
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

    @Nullable
    static GenericStack recoverExactInsertion(
            IItemHandler inventory,
            int slot,
            MalumRecipeInputMatcher.Assignment<AEItemKey> assignment) {
        return recoverExactInsertion(
                inventory,
                slot,
                new GenericStack(assignment.value(), assignment.amount()));
    }

    @Nullable
    static GenericStack recoverExactInsertion(
            IItemHandler inventory,
            int slot,
            GenericStack accepted) {
        if (slot < 0 || slot >= inventory.getSlots()
                || !(accepted.what() instanceof AEItemKey expectedKey)
                || accepted.amount() <= 0
                || accepted.amount() > Integer.MAX_VALUE) {
            return null;
        }
        var current = inventory.getStackInSlot(slot);
        var expected = expectedKey.toStack((int) accepted.amount());
        if (current.isEmpty()
                || current.getCount() > expected.getCount()
                || !ItemStack.isSameItemSameTags(current, expected)) {
            return null;
        }
        var simulated = inventory.extractItem(slot, current.getCount(), true);
        if (simulated.isEmpty()
                || simulated.getCount() != current.getCount()
                || !ItemStack.isSameItemSameTags(simulated, current)) {
            return null;
        }
        var extracted = inventory.extractItem(slot, current.getCount(), false);
        if (extracted.isEmpty() || !ItemStack.isSameItemSameTags(extracted, current)) {
            return null;
        }
        return new GenericStack(AEItemKey.of(extracted), extracted.getCount());
    }

}
