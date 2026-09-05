package com.moakiee.ae2lt.packaged.logic.multiblock.ma;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

import org.jetbrains.annotations.Nullable;

/** Deterministic exact bipartite matcher that tries the tightest constraints first. */
final class ConstraintFirstMatcher {
    private ConstraintFirstMatcher() {
    }

    /**
     * Returns one input per constraint, in the constraints' original order.
     * Every input must be used exactly once.
     */
    @Nullable
    static <T> List<T> match(List<T> inputs, List<? extends Predicate<T>> constraints) {
        if (inputs.size() != constraints.size()) {
            return null;
        }

        var choices = new ArrayList<ConstraintChoices>(constraints.size());
        for (int constraintIndex = 0; constraintIndex < constraints.size(); constraintIndex++) {
            var matchingInputs = new ArrayList<Integer>();
            var constraint = constraints.get(constraintIndex);
            for (int inputIndex = 0; inputIndex < inputs.size(); inputIndex++) {
                if (constraint.test(inputs.get(inputIndex))) {
                    matchingInputs.add(inputIndex);
                }
            }
            if (matchingInputs.isEmpty()) {
                return null;
            }
            choices.add(new ConstraintChoices(constraintIndex, List.copyOf(matchingInputs)));
        }

        choices.sort(Comparator.comparingInt((ConstraintChoices choice) -> choice.inputIndexes().size())
                .thenComparingInt(ConstraintChoices::constraintIndex));

        var assignment = new int[constraints.size()];
        var usedInputs = new boolean[inputs.size()];
        if (!assign(0, choices, assignment, usedInputs)) {
            return null;
        }

        var result = new ArrayList<T>(constraints.size());
        for (int inputIndex : assignment) {
            result.add(inputs.get(inputIndex));
        }
        return List.copyOf(result);
    }

    private static boolean assign(int depth, List<ConstraintChoices> choices,
                                  int[] assignment, boolean[] usedInputs) {
        if (depth == choices.size()) {
            return true;
        }

        var choice = choices.get(depth);
        for (int inputIndex : choice.inputIndexes()) {
            if (usedInputs[inputIndex]) {
                continue;
            }
            usedInputs[inputIndex] = true;
            assignment[choice.constraintIndex()] = inputIndex;
            if (assign(depth + 1, choices, assignment, usedInputs)) {
                return true;
            }
            usedInputs[inputIndex] = false;
        }
        return false;
    }

    private record ConstraintChoices(int constraintIndex, List<Integer> inputIndexes) {
    }
}
