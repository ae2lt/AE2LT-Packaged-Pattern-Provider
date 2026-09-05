package com.moakiee.ae2lt.packaged.logic.multiblock.ma;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;

class InfusionInputMatcherTest {
    @Test
    void preservesNineSlotLayoutAndLeavesEmptyPedestalsUnused() {
        var match = InfusionInputMatcher.match(
                List.of("pedestal-c", "altar", "pedestal-a"),
                slots(
                        exact("altar"),
                        exact("pedestal-a"),
                        null,
                        exact("pedestal-c"),
                        null,
                        null,
                        null,
                        null,
                        null));

        assertNotNull(match);
        assertEquals(9, match.size());
        assertEquals("altar", match.get(0));
        assertEquals("pedestal-a", match.get(1));
        assertNull(match.get(2));
        assertEquals("pedestal-c", match.get(3));
        assertNull(match.get(8));
    }

    @Test
    void usesConstraintFirstBacktrackingForOverlappingIngredients() {
        var match = InfusionInputMatcher.match(
                List.of("specific", "altar", "broad-only"),
                slots(
                        exact("altar"),
                        value -> value.equals("specific") || value.equals("broad-only"),
                        exact("specific"),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null));

        assertNotNull(match);
        assertEquals("broad-only", match.get(1));
        assertEquals("specific", match.get(2));
    }

    @Test
    void rejectsInputForIngredientEmptyPedestalSlot() {
        assertNull(InfusionInputMatcher.match(
                List.of("altar", "required", "extra"),
                slots(
                        exact("altar"),
                        exact("required"),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null)));
    }

    @SafeVarargs
    private static List<Predicate<String>> slots(Predicate<String>... constraints) {
        return java.util.Arrays.asList(constraints);
    }

    private static Predicate<String> exact(String expected) {
        return value -> value.equals(expected);
    }
}
