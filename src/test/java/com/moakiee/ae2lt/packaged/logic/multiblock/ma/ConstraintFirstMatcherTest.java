package com.moakiee.ae2lt.packaged.logic.multiblock.ma;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;

class ConstraintFirstMatcherTest {
    @Test
    void matchesNarrowConstraintBeforeOverlappingBroadConstraint() {
        var match = ConstraintFirstMatcher.match(
                List.of("specific", "broad-only"),
                List.of(
                        value -> value.equals("specific") || value.equals("broad-only"),
                        value -> value.equals("specific")));

        assertEquals(List.of("broad-only", "specific"), match);
    }

    @Test
    void backtracksWhenFirstEqualWidthChoiceDeadEnds() {
        var match = ConstraintFirstMatcher.match(
                List.of("a", "b", "c"),
                List.of(
                        value -> value.equals("a") || value.equals("b"),
                        value -> value.equals("a") || value.equals("c"),
                        value -> value.equals("a") || value.equals("c")));

        assertEquals(List.of("b", "a", "c"), match);
    }

    @Test
    void resultIsDeterministicForEquivalentConstraints() {
        List<Predicate<String>> constraints = List.of(value -> true, value -> true);

        assertEquals(List.of("first", "second"),
                ConstraintFirstMatcher.match(List.of("first", "second"), constraints));
        assertEquals(List.of("first", "second"),
                ConstraintFirstMatcher.match(List.of("first", "second"), constraints));
    }

    @Test
    void rejectsUnusedExtraInput() {
        assertNull(ConstraintFirstMatcher.match(
                List.of("required", "extra"),
                List.<Predicate<String>>of(value -> value.equals("required"))));
    }

    @Test
    void rejectsReusingOneInputForTwoConstraints() {
        assertNull(ConstraintFirstMatcher.match(
                List.of("only"),
                List.of(value -> value.equals("only"), value -> value.equals("only"))));
    }
}
