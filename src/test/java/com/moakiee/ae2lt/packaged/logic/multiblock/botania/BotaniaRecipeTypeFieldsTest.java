package com.moakiee.ae2lt.packaged.logic.multiblock.botania;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BotaniaRecipeTypeFieldsTest {

    @Test
    void shouldPreferCurrentRecipeTypeNames() throws ReflectiveOperationException {
        assertSame(
                BothRecipeTypeNames.PETAL_APOTHECARY_TYPE,
                BotaniaRecipeTypeFields.staticFieldValue(
                        BothRecipeTypeNames.class, "PETAL_APOTHECARY_TYPE", "PETAL_TYPE"));
        assertSame(
                BothRecipeTypeNames.RUNIC_ALTAR_TYPE,
                BotaniaRecipeTypeFields.staticFieldValue(
                        BothRecipeTypeNames.class, "RUNIC_ALTAR_TYPE", "RUNE_TYPE"));
    }

    @Test
    void shouldFallBackToLegacyRecipeTypeNames() throws ReflectiveOperationException {
        assertSame(
                LegacyRecipeTypeNames.PETAL_TYPE,
                BotaniaRecipeTypeFields.staticFieldValue(
                        LegacyRecipeTypeNames.class, "PETAL_APOTHECARY_TYPE", "PETAL_TYPE"));
        assertSame(
                LegacyRecipeTypeNames.RUNE_TYPE,
                BotaniaRecipeTypeFields.staticFieldValue(
                        LegacyRecipeTypeNames.class, "RUNIC_ALTAR_TYPE", "RUNE_TYPE"));
    }

    @Test
    void shouldDescribeEveryMissingAlias() {
        var failure = assertThrows(
                NoSuchFieldException.class,
                () -> BotaniaRecipeTypeFields.staticFieldValue(
                        MissingRecipeTypeNames.class, "PETAL_APOTHECARY_TYPE", "PETAL_TYPE"));

        assertTrue(failure.getMessage().contains("PETAL_APOTHECARY_TYPE"));
        assertTrue(failure.getMessage().contains("PETAL_TYPE"));
    }

    static final class BothRecipeTypeNames {
        public static final Object PETAL_APOTHECARY_TYPE = new Object();
        public static final Object PETAL_TYPE = new Object();
        public static final Object RUNIC_ALTAR_TYPE = new Object();
        public static final Object RUNE_TYPE = new Object();
    }

    static final class LegacyRecipeTypeNames {
        public static final Object PETAL_TYPE = new Object();
        public static final Object RUNE_TYPE = new Object();
    }

    static final class MissingRecipeTypeNames {
    }
}
