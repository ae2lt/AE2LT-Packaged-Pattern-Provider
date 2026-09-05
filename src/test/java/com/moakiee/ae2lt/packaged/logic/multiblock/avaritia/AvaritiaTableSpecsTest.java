package com.moakiee.ae2lt.packaged.logic.multiblock.avaritia;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class AvaritiaTableSpecsTest {

    @Test
    void preservesReAvaritia141TableMapping() {
        assertEquals(List.of(
                new AvaritiaTableSpecs.TableSpec(
                        AvaritiaTableSpecs.TableKind.SCULK, "sculk_crafting_table", 3, 1),
                new AvaritiaTableSpecs.TableSpec(
                        AvaritiaTableSpecs.TableKind.NETHER, "nether_crafting_table", 5, 2),
                new AvaritiaTableSpecs.TableSpec(
                        AvaritiaTableSpecs.TableKind.END, "end_crafting_table", 7, 3),
                new AvaritiaTableSpecs.TableSpec(
                        AvaritiaTableSpecs.TableKind.EXTREME, "extreme_crafting_table", 9, 4)),
                AvaritiaTableSpecs.all());
    }

    @Test
    void mapsOnlyKnownAvaritiaBlockIds() {
        assertEquals(AvaritiaTableSpecs.TableKind.SCULK,
                AvaritiaTableSpecs.find("avaritia", "sculk_crafting_table").kind());
        assertEquals(AvaritiaTableSpecs.TableKind.EXTREME,
                AvaritiaTableSpecs.find("avaritia", "extreme_crafting_table").kind());
        assertNull(AvaritiaTableSpecs.find("minecraft", "extreme_crafting_table"));
        assertNull(AvaritiaTableSpecs.find("avaritia", "unknown_crafting_table"));
    }

    @Test
    void preservesExactAndMinimumTierRulesForEndAndExtremeTables() {
        var sculk = AvaritiaTableSpecs.all().get(0);
        var nether = AvaritiaTableSpecs.all().get(1);
        var end = AvaritiaTableSpecs.all().get(2);
        var extreme = AvaritiaTableSpecs.all().get(3);

        assertTrue(sculk.canCraftRecipeTier(1, true));
        assertFalse(sculk.canCraftRecipeTier(2, true));
        assertTrue(nether.canCraftRecipeTier(2, true));
        assertTrue(nether.canCraftRecipeTier(1, false));
        assertTrue(end.canCraftRecipeTier(3, true));
        assertTrue(extreme.canCraftRecipeTier(4, true));
        assertFalse(end.canCraftRecipeTier(4, true));
        assertTrue(extreme.canCraftRecipeTier(0, false));
    }

    @Test
    void distinguishesUntieredKnownAndUnknownTieredRecipes() {
        var specs = AvaritiaTableSpecs.all();

        assertTrue(specs.get(0).canCraftRecipeTier(AvaritiaTableSpecs.RecipeTier.untiered()));
        for (int tier = 1; tier <= 4; tier++) {
            assertTrue(specs.get(tier - 1).canCraftRecipeTier(
                    AvaritiaTableSpecs.RecipeTier.known(tier, true)));
        }
        assertFalse(specs.get(0).canCraftRecipeTier(AvaritiaTableSpecs.RecipeTier.known(2, true)));
        assertFalse(specs.get(0).canCraftRecipeTier(AvaritiaTableSpecs.RecipeTier.unknownTiered()));
    }

    @Test
    void rejectsPartiallyKnownTierMetadata() {
        var exception = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new AvaritiaTableSpecs.RecipeTier(true, 1, null));
        assertTrue(exception.getMessage().contains("known together"));
    }

    @Test
    void requiresExactInventorySlotCounts() {
        var expectedSlots = List.of(9, 25, 49, 81);
        for (int i = 0; i < AvaritiaTableSpecs.all().size(); i++) {
            var spec = AvaritiaTableSpecs.all().get(i);
            int slots = expectedSlots.get(i);
            assertEquals(slots, spec.slots());
            assertTrue(spec.acceptsSlotCount(slots));
            assertFalse(spec.acceptsSlotCount(slots - 1));
            assertFalse(spec.acceptsSlotCount(slots + 1));
        }
    }
}
