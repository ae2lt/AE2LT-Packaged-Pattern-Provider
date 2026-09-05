package com.moakiee.ae2lt.packaged.logic.multiblock.avaritia;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class AvaritiaTableRemainderPlannerTest {

    @Test
    void acceptsContainerRemainderAfterConsumedSingleItem() {
        assertTrue(AvaritiaTableRemainderPlanner.canApply(
                List.of(stack("water_bucket", 1, 1)),
                List.of(stack("bucket", 1, 16)),
                String::equals));
    }

    @Test
    void combinesMatchingRemainderWithUnconsumedStack() {
        assertTrue(AvaritiaTableRemainderPlanner.canApply(
                List.of(stack("tool", 2, 16)),
                List.of(stack("tool", 1, 16)),
                String::equals));
    }

    @Test
    void rejectsDifferentRemainderWhenConsumedStackStillOccupiesSlot() {
        assertFalse(AvaritiaTableRemainderPlanner.canApply(
                List.of(stack("water_bucket", 2, 16)),
                List.of(stack("bucket", 1, 16)),
                String::equals));
    }

    @Test
    void acceptsConsumedSlotWithNoRemainder() {
        assertTrue(AvaritiaTableRemainderPlanner.canApply(
                List.of(stack("tool", 1, 1)),
                List.of(AvaritiaTableRemainderPlanner.Stack.empty()),
                String::equals));
    }

    @Test
    void acceptsAllConsumedSlotsWhenOnlySomeHaveRemainders() {
        assertTrue(AvaritiaTableRemainderPlanner.canApply(
                List.of(stack("tool", 1, 1), stack("tool", 1, 1)),
                List.of(AvaritiaTableRemainderPlanner.Stack.empty(),
                        stack("tool", 1, 1)),
                String::equals));
    }

    @Test
    void rejectsOverflowAndRemainderInOriginallyEmptySlot() {
        assertFalse(AvaritiaTableRemainderPlanner.canApply(
                List.of(stack("tool", 16, 16)),
                List.of(stack("tool", 2, 16)),
                String::equals));
        assertFalse(AvaritiaTableRemainderPlanner.canApply(
                List.of(AvaritiaTableRemainderPlanner.Stack.empty()),
                List.of(stack("bucket", 1, 16)),
                String::equals));
    }

    private static AvaritiaTableRemainderPlanner.Stack<String> stack(
            String key, int count, int maxStackSize) {
        return new AvaritiaTableRemainderPlanner.Stack<>(key, count, maxStackSize);
    }
}
