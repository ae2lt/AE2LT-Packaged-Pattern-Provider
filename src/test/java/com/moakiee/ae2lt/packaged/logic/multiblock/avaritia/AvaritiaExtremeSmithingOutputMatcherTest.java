package com.moakiee.ae2lt.packaged.logic.multiblock.avaritia;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AvaritiaExtremeSmithingOutputMatcherTest {

    @Test
    void matchesOutputIdentityAfterDroppingSecondaryData() {
        assertTrue(AvaritiaExtremeSmithingOutputMatcher.matches(
                2, "sword:expected-components",
                2, "sword:actual-components",
                key -> key.substring(0, key.indexOf(':'))));
    }

    @Test
    void stillRequiresExactBatchAmountAndItemIdentity() {
        assertFalse(AvaritiaExtremeSmithingOutputMatcher.matches(
                2, "sword:expected",
                1, "sword:actual",
                key -> key.substring(0, key.indexOf(':'))));
        assertFalse(AvaritiaExtremeSmithingOutputMatcher.matches(
                2, "sword:expected",
                2, "pickaxe:actual",
                key -> key.substring(0, key.indexOf(':'))));
    }
}
