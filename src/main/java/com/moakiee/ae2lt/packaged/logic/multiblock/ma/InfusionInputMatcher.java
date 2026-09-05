package com.moakiee.ae2lt.packaged.logic.multiblock.ma;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;

import org.jetbrains.annotations.Nullable;

/** Pure matcher for the altar-plus-eight-pedestal infusion layout. */
final class InfusionInputMatcher {
    static final int LAYOUT_SIZE = 9;

    private InfusionInputMatcher() {
    }

    /**
     * Matches all supplied inputs to the non-empty slot constraints and returns
     * the complete nine-slot layout. A {@code null} constraint denotes an
     * {@code Ingredient.EMPTY} slot and remains {@code null} in the result.
     */
    @Nullable
    static <T> List<@Nullable T> match(
            List<T> inputs, List<? extends @Nullable Predicate<T>> slotConstraints) {
        if (slotConstraints.size() != LAYOUT_SIZE || slotConstraints.get(0) == null) {
            return null;
        }

        var occupiedSlots = new ArrayList<Integer>(LAYOUT_SIZE);
        var constraints = new ArrayList<Predicate<T>>(LAYOUT_SIZE);
        for (int slot = 0; slot < slotConstraints.size(); slot++) {
            var constraint = slotConstraints.get(slot);
            if (constraint != null) {
                occupiedSlots.add(slot);
                constraints.add(constraint);
            }
        }

        var assigned = ConstraintFirstMatcher.match(inputs, constraints);
        if (assigned == null) {
            return null;
        }

        var layout = new ArrayList<T>(Collections.nCopies(LAYOUT_SIZE, null));
        for (int i = 0; i < assigned.size(); i++) {
            layout.set(occupiedSlots.get(i), assigned.get(i));
        }
        return Collections.unmodifiableList(layout);
    }
}
