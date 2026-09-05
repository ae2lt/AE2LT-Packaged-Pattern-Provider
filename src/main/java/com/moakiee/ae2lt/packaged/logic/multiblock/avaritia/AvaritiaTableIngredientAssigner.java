package com.moakiee.ae2lt.packaged.logic.multiblock.avaritia;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.BiPredicate;

import org.jetbrains.annotations.Nullable;

final class AvaritiaTableIngredientAssigner {

    private AvaritiaTableIngredientAssigner() {
    }

    @Nullable
    static <I, K> List<K> assign(List<I> ingredients,
                                 List<Input<K>> inputs,
                                 long amountPerIngredient,
                                 BiPredicate<I, K> matcher) {
        Objects.requireNonNull(ingredients, "ingredients");
        Objects.requireNonNull(inputs, "inputs");
        Objects.requireNonNull(matcher, "matcher");
        if (amountPerIngredient <= 0) {
            return null;
        }

        var units = new ArrayList<K>();
        for (var input : inputs) {
            if (input.amount() == 0) {
                continue;
            }
            if (input.amount() % amountPerIngredient != 0) {
                return null;
            }
            long copies = input.amount() / amountPerIngredient;
            if (copies > ingredients.size() - units.size()) {
                return null;
            }
            for (long i = 0; i < copies; i++) {
                units.add(input.key());
            }
        }
        if (units.size() != ingredients.size()) {
            return null;
        }

        var candidates = new boolean[ingredients.size()][units.size()];
        var candidateCounts = new int[ingredients.size()];
        for (int ingredientIndex = 0; ingredientIndex < ingredients.size(); ingredientIndex++) {
            for (int unitIndex = 0; unitIndex < units.size(); unitIndex++) {
                if (matcher.test(ingredients.get(ingredientIndex), units.get(unitIndex))) {
                    candidates[ingredientIndex][unitIndex] = true;
                    candidateCounts[ingredientIndex]++;
                }
            }
        }

        var orderedIngredientIndexes = new ArrayList<Integer>(ingredients.size());
        for (int i = 0; i < ingredients.size(); i++) {
            orderedIngredientIndexes.add(i);
        }
        orderedIngredientIndexes.sort(java.util.Comparator
                .comparingInt((Integer index) -> candidateCounts[index])
                .thenComparingInt(Integer::intValue));

        // Stable Kuhn augmenting paths give a maximum matching without factorial search.
        var ingredientByUnit = new int[units.size()];
        Arrays.fill(ingredientByUnit, -1);
        for (int ingredientIndex : orderedIngredientIndexes) {
            if (!augment(ingredientIndex, candidates, ingredientByUnit,
                    new boolean[units.size()])) {
                return null;
            }
        }

        var chosenUnitByIngredient = new int[ingredients.size()];
        Arrays.fill(chosenUnitByIngredient, -1);
        for (int unitIndex = 0; unitIndex < ingredientByUnit.length; unitIndex++) {
            int ingredientIndex = ingredientByUnit[unitIndex];
            if (ingredientIndex >= 0) {
                chosenUnitByIngredient[ingredientIndex] = unitIndex;
            }
        }

        var assigned = new ArrayList<K>(ingredients.size());
        for (int i = 0; i < ingredients.size(); i++) {
            int unitIndex = chosenUnitByIngredient[i];
            if (unitIndex < 0) {
                return null;
            }
            assigned.add(units.get(unitIndex));
        }
        return List.copyOf(assigned);
    }

    private static boolean augment(int ingredientIndex,
                                   boolean[][] candidates,
                                   int[] ingredientByUnit,
                                   boolean[] visitedUnits) {
        for (int unitIndex = 0; unitIndex < ingredientByUnit.length; unitIndex++) {
            if (!candidates[ingredientIndex][unitIndex] || visitedUnits[unitIndex]) {
                continue;
            }
            visitedUnits[unitIndex] = true;
            int displacedIngredient = ingredientByUnit[unitIndex];
            if (displacedIngredient < 0
                    || augment(displacedIngredient, candidates, ingredientByUnit, visitedUnits)) {
                ingredientByUnit[unitIndex] = ingredientIndex;
                return true;
            }
        }
        return false;
    }

    record Input<K>(K key, long amount) {
        Input {
            Objects.requireNonNull(key, "key");
            if (amount < 0) {
                throw new IllegalArgumentException("amount must be non-negative");
            }
        }
    }
}
