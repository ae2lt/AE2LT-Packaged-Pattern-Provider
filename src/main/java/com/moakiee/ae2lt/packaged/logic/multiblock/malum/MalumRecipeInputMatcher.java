package com.moakiee.ae2lt.packaged.logic.multiblock.malum;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

import org.jetbrains.annotations.Nullable;

final class MalumRecipeInputMatcher {

    private MalumRecipeInputMatcher() {
    }

    @Nullable
    static <T> Match<T> match(List<Input<T>> inputs,
                              Requirement<T> main,
                              List<Requirement<T>> spirits,
                              List<Requirement<T>> extras) {
        var requirements = new ArrayList<Requirement<T>>(1 + spirits.size() + extras.size());
        requirements.add(main);
        requirements.addAll(spirits);
        requirements.addAll(extras);
        var assignments = matchAll(inputs, requirements);
        if (assignments == null) {
            return null;
        }

        return new Match<>(
                assignments.get(0),
                List.copyOf(assignments.subList(1, 1 + spirits.size())),
                List.copyOf(assignments.subList(1 + spirits.size(), assignments.size())));
    }

    @Nullable
    static <T> List<Assignment<T>> matchAll(List<Input<T>> inputs, List<Requirement<T>> requirements) {
        if (requirements.stream().anyMatch(requirement -> requirement.amount() <= 0)) {
            return null;
        }

        var remaining = new ArrayList<MutableInput<T>>(inputs.size());
        for (var input : inputs) {
            if (input.amount() > 0) {
                remaining.add(new MutableInput<>(input.value(), input.amount()));
            }
        }

        var assignments = new ArrayList<Assignment<T>>(requirements.size());
        if (!assign(0, requirements, remaining, assignments)) {
            return null;
        }

        return List.copyOf(assignments);
    }

    private static <T> boolean assign(int requirementIndex,
                                      List<Requirement<T>> requirements,
                                      List<MutableInput<T>> remaining,
                                      List<Assignment<T>> assignments) {
        if (requirementIndex >= requirements.size()) {
            return remaining.stream().allMatch(input -> input.amount == 0);
        }

        var requirement = requirements.get(requirementIndex);
        for (var input : remaining) {
            if (input.amount < requirement.amount() || !requirement.matches().test(input.value)) {
                continue;
            }

            input.amount -= requirement.amount();
            assignments.add(new Assignment<>(input.value, requirement.amount()));
            if (assign(requirementIndex + 1, requirements, remaining, assignments)) {
                return true;
            }
            assignments.remove(assignments.size() - 1);
            input.amount += requirement.amount();
        }
        return false;
    }

    record Input<T>(T value, long amount) {
        Input {
            Objects.requireNonNull(value, "value");
        }
    }

    record Requirement<T>(long amount, Predicate<T> matches) {
        Requirement {
            Objects.requireNonNull(matches, "matches");
        }
    }

    record Assignment<T>(T value, long amount) {
        Assignment {
            Objects.requireNonNull(value, "value");
        }
    }

    record Match<T>(Assignment<T> main, List<Assignment<T>> spirits, List<Assignment<T>> extras) {
        Match {
            Objects.requireNonNull(main, "main");
            spirits = List.copyOf(spirits);
            extras = List.copyOf(extras);
        }
    }

    private static final class MutableInput<T> {
        private final T value;
        private long amount;

        private MutableInput(T value, long amount) {
            this.value = Objects.requireNonNull(value, "value");
            this.amount = amount;
        }
    }
}
