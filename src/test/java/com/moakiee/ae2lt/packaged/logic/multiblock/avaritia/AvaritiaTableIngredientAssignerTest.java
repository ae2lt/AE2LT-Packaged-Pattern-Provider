package com.moakiee.ae2lt.packaged.logic.multiblock.avaritia;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class AvaritiaTableIngredientAssignerTest {

    @Test
    void backtracksWhenBroadIngredientOverlapsNarrowIngredient() {
        var assigned = AvaritiaTableIngredientAssigner.assign(
                List.of("any_ingot", "iron_only"),
                List.of(
                        new AvaritiaTableIngredientAssigner.Input<>("iron", 1),
                        new AvaritiaTableIngredientAssigner.Input<>("gold", 1)),
                1,
                (ingredient, input) -> switch (ingredient) {
                    case "any_ingot" -> input.equals("iron") || input.equals("gold");
                    case "iron_only" -> input.equals("iron");
                    default -> false;
                });

        assertEquals(List.of("gold", "iron"), assigned);
    }

    @Test
    void expandsGroupedPatternAmountsByCraftRatio() {
        var assigned = AvaritiaTableIngredientAssigner.assign(
                List.of("a", "a", "b"),
                List.of(
                        new AvaritiaTableIngredientAssigner.Input<>("a", 4),
                        new AvaritiaTableIngredientAssigner.Input<>("b", 2)),
                2,
                String::equals);

        assertEquals(List.of("a", "a", "b"), assigned);
    }

    @Test
    void rejectsAmountsThatCannotRepresentWholeCrafts() {
        assertNull(AvaritiaTableIngredientAssigner.assign(
                List.of("a", "a"),
                List.of(new AvaritiaTableIngredientAssigner.Input<>("a", 3)),
                2,
                String::equals));
    }

    @Test
    void rejectsUnassignedExtraInputs() {
        assertNull(AvaritiaTableIngredientAssigner.assign(
                List.of("a"),
                List.of(
                        new AvaritiaTableIngredientAssigner.Input<>("a", 1),
                        new AvaritiaTableIngredientAssigner.Input<>("b", 1)),
                1,
                String::equals));
    }

    @Test
    void usesDeterministicMaximumMatchingForLargeOverlappingInput() {
        var ingredients = new ArrayList<String>();
        var inputs = new ArrayList<AvaritiaTableIngredientAssigner.Input<String>>();
        for (int i = 0; i < 81; i++) {
            ingredients.add(i == 80 ? "narrow" : "broad");
            inputs.add(new AvaritiaTableIngredientAssigner.Input<>("unit_" + i, 1));
        }

        var matcherCalls = new AtomicInteger();
        var assigned = AvaritiaTableIngredientAssigner.assign(
                ingredients, inputs, 1,
                (ingredient, input) -> {
                    matcherCalls.incrementAndGet();
                    return ingredient.equals("broad") || input.equals("unit_80");
                });

        assertEquals("unit_80", assigned.get(80));
        assertEquals(81 * 81, matcherCalls.get());
        assertTrue(assigned.stream().distinct().count() == 81);
    }
}
