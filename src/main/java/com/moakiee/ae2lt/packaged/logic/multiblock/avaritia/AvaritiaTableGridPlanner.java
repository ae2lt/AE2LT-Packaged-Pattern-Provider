package com.moakiee.ae2lt.packaged.logic.multiblock.avaritia;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.BiPredicate;

import org.jetbrains.annotations.Nullable;

final class AvaritiaTableGridPlanner {

    private AvaritiaTableGridPlanner() {
    }

    static <T> List<Assignment<T>> placeSequential(int gridSize, List<T> values) {
        if (gridSize <= 0 || values.size() > gridSize * gridSize) {
            return List.of();
        }
        var assignments = new ArrayList<Assignment<T>>(values.size());
        for (int slot = 0; slot < values.size(); slot++) {
            assignments.add(new Assignment<>(slot, values.get(slot)));
        }
        return List.copyOf(assignments);
    }

    static <T> List<Assignment<T>> place(int recipeWidth, int gridSize, List<@Nullable T> recipeCells) {
        if (recipeWidth <= 0 || gridSize <= 0 || recipeWidth > gridSize) {
            return List.of();
        }

        var assignments = new ArrayList<Assignment<T>>();
        for (int recipeIndex = 0; recipeIndex < recipeCells.size(); recipeIndex++) {
            var value = recipeCells.get(recipeIndex);
            if (value == null) {
                continue;
            }

            int recipeX = recipeIndex % recipeWidth;
            int recipeY = recipeIndex / recipeWidth;
            if (recipeY >= gridSize) {
                return List.of();
            }
            assignments.add(new Assignment<>(recipeX + recipeY * gridSize, value));
        }
        return List.copyOf(assignments);
    }

    static <I, T> List<Assignment<T>> placeMatching(int recipeWidth,
                                                    int gridSize,
                                                    List<@Nullable I> recipeCells,
                                                    List<T> inputs,
                                                    BiPredicate<I, T> matcher) {
        if (recipeWidth <= 0 || gridSize <= 0 || recipeWidth > gridSize) {
            return List.of();
        }

        var cellIndexes = new ArrayList<Integer>();
        for (int recipeIndex = 0; recipeIndex < recipeCells.size(); recipeIndex++) {
            if (recipeCells.get(recipeIndex) == null) {
                continue;
            }

            int recipeY = recipeIndex / recipeWidth;
            if (recipeY >= gridSize) {
                return List.of();
            }
            cellIndexes.add(recipeIndex);
        }
        if (cellIndexes.size() != inputs.size()) {
            return List.of();
        }

        cellIndexes.sort(Comparator.comparingInt(index ->
                candidateCount(recipeCells.get(index), inputs, matcher)));

        var used = new boolean[inputs.size()];
        var chosenInputIndexes = new int[cellIndexes.size()];
        if (!assignInputs(0, recipeCells, inputs, matcher, cellIndexes, used, chosenInputIndexes)) {
            return List.of();
        }

        var assignments = new ArrayList<Assignment<T>>();
        for (int i = 0; i < chosenInputIndexes.length; i++) {
            int recipeIndex = cellIndexes.get(i);
            int targetSlot = (recipeIndex % recipeWidth) + (recipeIndex / recipeWidth) * gridSize;
            assignments.add(new Assignment<>(targetSlot, inputs.get(chosenInputIndexes[i])));
        }
        assignments.sort(Comparator.comparingInt(Assignment::slot));
        return List.copyOf(assignments);
    }

    private static <I, T> int candidateCount(@Nullable I ingredient,
                                             List<T> inputs,
                                             BiPredicate<I, T> matcher) {
        if (ingredient == null) {
            return 0;
        }
        int count = 0;
        for (var input : inputs) {
            if (matcher.test(ingredient, input)) {
                count++;
            }
        }
        return count;
    }

    private static <I, T> boolean assignInputs(int cellNumber,
                                               List<@Nullable I> recipeCells,
                                               List<T> inputs,
                                               BiPredicate<I, T> matcher,
                                               List<Integer> cellIndexes,
                                               boolean[] used,
                                               int[] chosenInputIndexes) {
        if (cellNumber >= cellIndexes.size()) {
            return true;
        }

        var ingredient = recipeCells.get(cellIndexes.get(cellNumber));
        if (ingredient == null) {
            return false;
        }

        for (int inputIndex = 0; inputIndex < inputs.size(); inputIndex++) {
            if (used[inputIndex] || !matcher.test(ingredient, inputs.get(inputIndex))) {
                continue;
            }

            used[inputIndex] = true;
            chosenInputIndexes[cellNumber] = inputIndex;
            if (assignInputs(cellNumber + 1, recipeCells, inputs, matcher,
                    cellIndexes, used, chosenInputIndexes)) {
                return true;
            }
            used[inputIndex] = false;
        }
        return false;
    }

    record Assignment<T>(int slot, T value) {
    }
}
