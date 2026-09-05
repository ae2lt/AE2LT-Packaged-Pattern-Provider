package com.moakiee.ae2lt.packaged.logic.multiblock.avaritia;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import org.junit.jupiter.api.Test;

class AvaritiaExtremeSmithingInputMatcherTest {

    @Test
    void assignsTemplateBaseAndExactlyThreeAdditionsRegardlessOfInputOrder() {
        var match = AvaritiaExtremeSmithingInputMatcher.match(
                List.of("addition-1", "base", "addition-2", "template", "addition-3"),
                value -> value.equals("template"),
                value -> value.equals("base"),
                value -> value.startsWith("addition-"));

        assertEquals("template", match.template());
        assertEquals("base", match.base());
        assertEquals(List.of("addition-1", "addition-2", "addition-3"), match.additions());
    }

    @Test
    void backtracksWhenTemplateCandidateCanAlsoBeBase() {
        var match = AvaritiaExtremeSmithingInputMatcher.match(
                List.of("shared", "template-only", "addition-1", "addition-2", "addition-3"),
                value -> value.equals("shared") || value.equals("template-only"),
                value -> value.equals("shared"),
                value -> value.startsWith("addition-"));

        assertEquals("template-only", match.template());
        assertEquals("shared", match.base());
    }

    @Test
    void rejectsAnyInputCountOtherThanFive() {
        assertNull(AvaritiaExtremeSmithingInputMatcher.match(
                List.of("template", "base", "addition-1", "addition-2"),
                value -> value.equals("template"),
                value -> value.equals("base"),
                value -> value.startsWith("addition-")));
    }
}
