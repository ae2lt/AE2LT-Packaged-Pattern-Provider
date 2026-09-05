package com.moakiee.ae2lt.packaged.logic.multiblock;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonParser;

class CompatibilityResourceContractTest {
    @Test
    void mysticalAdaptationsRecipeSkipsWhenRequiredModsOrMachineAreMissing() throws IOException {
        var recipeText = read("src/main/resources/data/mysticaladaptations/recipes/insanium_reprocessor.json");
        var root = JsonParser.parseString(recipeText).getAsJsonObject();
        var entries = root.getAsJsonArray("recipes");
        var conditions = entries.get(0).getAsJsonObject().getAsJsonArray("conditions");
        Set<String> requiredMods = new HashSet<>();
        var hasMachineCondition = false;
        for (var conditionElement : conditions) {
            var condition = conditionElement.getAsJsonObject();
            var type = condition.get("type").getAsString();
            if ("forge:mod_loaded".equals(type)) {
                requiredMods.add(condition.get("modid").getAsString());
            }
            if ("forge:item_exists".equals(type)
                    && "mysticalagriculture:awakened_supremium_reprocessor"
                            .equals(condition.get("item").getAsString())) {
                hasMachineCondition = true;
            }
        }

        assertTrue(requiredMods.contains("mysticaladaptations"));
        assertTrue(requiredMods.contains("mysticalagradditions"));
        assertTrue(hasMachineCondition);
        assertTrue(recipeText.contains("mysticaladaptations:insanium_reprocessor"));
        assertTrue(recipeText.contains("mysticalagradditions:insanium_essence"));
        assertFalse(recipeText.contains("mysticalagriculture:seed_reprocessor"));
    }

    @Test
    void avaritiaPackagedCoresUseRuntimeDerivedTiers() throws IOException {
        assertTrue(read("src/main/resources/data/ae2ltpp/recipes/packaged_core/avaritia_sculk_table_packaged_core.json")
                .contains("\"tier\": 1"));
        assertTrue(read("src/main/resources/data/ae2ltpp/recipes/packaged_core/avaritia_nether_table_packaged_core.json")
                .contains("\"tier\": 2"));
        assertTrue(read("src/main/resources/data/ae2ltpp/recipes/packaged_core/avaritia_end_table_packaged_core.json")
                .contains("\"tier\": 3"));
        assertTrue(read("src/main/resources/data/ae2ltpp/recipes/packaged_core/avaritia_extreme_table_packaged_core.json")
                .contains("\"tier\": 4"));
    }

    @Test
    void mysticalAdaptationsLoadsAfterItsOriginalResources() throws IOException {
        var metadata = read("src/main/resources/META-INF/mods.toml");
        int dependency = metadata.indexOf("modId = \"mysticaladaptations\"");

        assertTrue(dependency >= 0);
        var block = metadata.substring(dependency, Math.min(metadata.length(), dependency + 160));
        assertTrue(block.contains("mandatory = false"));
        assertTrue(block.contains("ordering = \"AFTER\""));
        assertTrue(block.contains("side = \"BOTH\""));
    }

    private static String read(String path) throws IOException {
        return Files.readString(Path.of(path));
    }
}
