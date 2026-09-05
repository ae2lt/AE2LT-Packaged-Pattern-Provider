package com.moakiee.ae2lt.packaged.logic.multiblock.avaritia;

import java.util.List;

import org.jetbrains.annotations.Nullable;

/**
 * Versioned, pure-Java description of the Re-Avaritia 1.4.1 table layouts.
 */
final class AvaritiaTableSpecs {

    private static final String MOD_ID = "avaritia";

    private static final List<TableSpec> TABLES = List.of(
            // Re-Avaritia 1.4.1 exposes these exact handler layouts. Keep the
            // profile per block instead of deriving one layout from another.
            // Re-Avaritia derives runtime tiers from handler sizes: 9/25/49/81
            // slots correspond to tiers 1/2/3/4 respectively.
            new TableSpec(TableKind.SCULK, "sculk_crafting_table", 3, 1),
            new TableSpec(TableKind.NETHER, "nether_crafting_table", 5, 2),
            new TableSpec(TableKind.END, "end_crafting_table", 7, 3),
            new TableSpec(TableKind.EXTREME, "extreme_crafting_table", 9, 4));

    private AvaritiaTableSpecs() {
    }

    static List<TableSpec> all() {
        return TABLES;
    }

    @Nullable
    static TableSpec find(String namespace, String path) {
        if (!MOD_ID.equals(namespace)) {
            return null;
        }
        for (var spec : TABLES) {
            if (spec.blockPath().equals(path)) {
                return spec;
            }
        }
        return null;
    }

    enum TableKind {
        SCULK,
        NETHER,
        END,
        EXTREME
    }

    record TableSpec(TableKind kind, String blockPath, int gridSize, int tier) {
        TableSpec {
            if (blockPath == null || blockPath.isBlank()) {
                throw new IllegalArgumentException("blockPath must not be blank");
            }
            if (gridSize <= 0) {
                throw new IllegalArgumentException("gridSize must be positive");
            }
            if (tier <= 0) {
                throw new IllegalArgumentException("tier must be positive");
            }
        }

        int slots() {
            return Math.multiplyExact(gridSize, gridSize);
        }

        boolean acceptsSlotCount(int actualSlots) {
            return actualSlots == slots();
        }

        boolean canCraftRecipeTier(int recipeTier, boolean requiredTier) {
            if (recipeTier <= 0) {
                return true;
            }
            return requiredTier ? tier == recipeTier : tier >= recipeTier;
        }

        boolean canCraftRecipeTier(RecipeTier recipeTier) {
            if (!recipeTier.tiered()) {
                return true;
            }
            if (!recipeTier.known()) {
                return false;
            }
            return canCraftRecipeTier(recipeTier.tier(), recipeTier.requiredTier());
        }
    }

    record RecipeTier(boolean tiered, @Nullable Integer tier, @Nullable Boolean requiredTier) {
        RecipeTier {
            if (!tiered && (tier != null || requiredTier != null)) {
                throw new IllegalArgumentException("non-tiered recipes cannot declare tier metadata");
            }
            if ((tier == null) != (requiredTier == null)) {
                throw new IllegalArgumentException("tier and requiredTier must be known together");
            }
            if (tier != null && tier <= 0) {
                throw new IllegalArgumentException("known recipe tier must be positive");
            }
        }

        static RecipeTier untiered() {
            return new RecipeTier(false, null, null);
        }

        static RecipeTier unknownTiered() {
            return new RecipeTier(true, null, null);
        }

        static RecipeTier known(int tier, boolean requiredTier) {
            return new RecipeTier(true, tier, requiredTier);
        }

        boolean known() {
            return tier != null;
        }
    }
}
