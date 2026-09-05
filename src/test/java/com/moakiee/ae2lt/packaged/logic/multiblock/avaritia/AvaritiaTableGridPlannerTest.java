package com.moakiee.ae2lt.packaged.logic.multiblock.avaritia;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

class AvaritiaTableGridPlannerTest {

    @Test
    void placesShapedRecipeIntoTopLeftOfLargerGrid() {
        assertEquals(List.of(
                new AvaritiaTableGridPlanner.Assignment<>(0, "a"),
                new AvaritiaTableGridPlanner.Assignment<>(1, "b"),
                new AvaritiaTableGridPlanner.Assignment<>(2, "c"),
                new AvaritiaTableGridPlanner.Assignment<>(7, "d"),
                new AvaritiaTableGridPlanner.Assignment<>(8, "e"),
                new AvaritiaTableGridPlanner.Assignment<>(9, "f")),
                AvaritiaTableGridPlanner.place(3, 7,
                        List.of("a", "b", "c", "d", "e", "f")));
    }

    @Test
    void preservesEmptyShapedCells() {
        assertEquals(List.of(
                new AvaritiaTableGridPlanner.Assignment<>(0, "a"),
                new AvaritiaTableGridPlanner.Assignment<>(6, "d")),
                AvaritiaTableGridPlanner.place(2, 5,
                        Arrays.asList("a", null, null, "d")));
    }

    @Test
    void placesShapelessValuesSequentially() {
        assertEquals(List.of(
                new AvaritiaTableGridPlanner.Assignment<>(0, "a"),
                new AvaritiaTableGridPlanner.Assignment<>(1, "b"),
                new AvaritiaTableGridPlanner.Assignment<>(2, "c")),
                AvaritiaTableGridPlanner.placeSequential(5, List.of("a", "b", "c")));
        assertTrue(AvaritiaTableGridPlanner.placeSequential(1, List.of("a", "b")).isEmpty());
    }

    @Test
    void backtracksForOverlappingShapedIngredients() {
        var result = AvaritiaTableGridPlanner.placeMatching(
                2,
                5,
                List.of("any_ingot", "iron_only"),
                List.of("iron", "gold"),
                (ingredient, input) -> switch (ingredient) {
                    case "any_ingot" -> input.equals("iron") || input.equals("gold");
                    case "iron_only" -> input.equals("iron");
                    default -> false;
                });

        assertEquals(List.of(
                new AvaritiaTableGridPlanner.Assignment<>(0, "gold"),
                new AvaritiaTableGridPlanner.Assignment<>(1, "iron")), result);
    }

    @Test
    void rejectsRecipeLayoutTallerThanTargetGrid() {
        assertTrue(AvaritiaTableGridPlanner.place(1, 2, List.of("a", "b", "c")).isEmpty());
    }
}
