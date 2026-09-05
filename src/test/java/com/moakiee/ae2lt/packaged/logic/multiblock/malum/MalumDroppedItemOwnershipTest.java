package com.moakiee.ae2lt.packaged.logic.multiblock.malum;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import net.minecraft.resources.ResourceLocation;

class MalumDroppedItemOwnershipTest {
    @Test
    void newEntityOwnsItsWholeStack() {
        assertEquals(4, MalumDroppedItemOwnership.attributableCount(4, 0));
    }

    @Test
    void preexistingEntityOwnsOnlyItsPositiveGrowth() {
        assertEquals(2, MalumDroppedItemOwnership.attributableCount(7, 5));
        assertEquals(0, MalumDroppedItemOwnership.attributableCount(5, 5));
        assertEquals(0, MalumDroppedItemOwnership.attributableCount(3, 5));
    }

    @Test
    void unrelatedBaselineLossDoesNotOffsetAnotherEntityGrowth() {
        int disappearedBaselineEntity = MalumDroppedItemOwnership.attributableCount(0, 5);
        int newCraftEntity = MalumDroppedItemOwnership.attributableCount(2, 0);

        assertEquals(0, disappearedBaselineEntity);
        assertEquals(2, newCraftEntity);
    }

    @Test
    void recipeMetadataRoundTrips() {
        var recipeId = ResourceLocation.fromNamespaceAndPath("malum", "focus_test");
        var part = "recipe=" + recipeId;

        assertTrue(MalumDroppedItemOwnership.isRecipeMetadata(part));
        assertEquals(recipeId, MalumDroppedItemOwnership.parseRecipeMetadata(part));
    }

    @Test
    void legacyEntityMetadataIsNotTreatedAsARecipe() {
        var entityPart = "12345678-1234-1234-1234-123456789abc=5";

        assertFalse(MalumDroppedItemOwnership.isRecipeMetadata(entityPart));
        assertNull(MalumDroppedItemOwnership.parseRecipeMetadata(entityPart));
    }

    @Test
    void malformedRecipeMetadataIsRejected() {
        assertTrue(MalumDroppedItemOwnership.isRecipeMetadata("recipe=Not A Recipe Id"));
        assertNull(MalumDroppedItemOwnership.parseRecipeMetadata("recipe=Not A Recipe Id"));
        assertNull(MalumDroppedItemOwnership.parseRecipeMetadata("recipe="));
    }
}
