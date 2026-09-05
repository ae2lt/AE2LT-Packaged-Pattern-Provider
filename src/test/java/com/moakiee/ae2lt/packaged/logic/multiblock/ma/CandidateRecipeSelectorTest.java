package com.moakiee.ae2lt.packaged.logic.multiblock.ma;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import org.junit.jupiter.api.Test;

class CandidateRecipeSelectorTest {
    @Test
    void choosesByPushInputsInsteadOfLockingFirstOutputCompatibleRecipe() {
        var selected = CandidateRecipeSelector.firstMatch(
                List.of("same-output-recipe-a", "same-output-recipe-b"),
                recipe -> recipe.endsWith("b") ? "matched-b-inputs" : null);

        assertEquals("matched-b-inputs", selected);
    }

    @Test
    void rejectsPushWhenNoRetainedCandidateMatches() {
        assertNull(CandidateRecipeSelector.firstMatch(
                List.of("recipe-a", "recipe-b"), recipe -> null));
    }
}
