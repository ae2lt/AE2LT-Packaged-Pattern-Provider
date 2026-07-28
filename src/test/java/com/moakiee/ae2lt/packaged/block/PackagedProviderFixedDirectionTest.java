package com.moakiee.ae2lt.packaged.block;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

class PackagedProviderFixedDirectionTest {
    private static final List<String> DIRECTIONS = List.of("down", "up", "north", "south", "west", "east");
    private static final String TEXTURE = "ae2ltpp:block/overload_packaged_pattern_provider";

    @Test
    void allPushDirectionVariantsRenderWithTheUndirectedModel() throws IOException {
        assertAllDirectionsUseBaseModel("packaged_pattern_provider");
        assertAllDirectionsUseBaseModel("wireless_packaged_pattern_provider");
    }

    @Test
    void blockModelsUseThePackagedProviderTexture() throws IOException {
        var packagedModel = compact(read("src/main/resources/assets/ae2ltpp/models/block/packaged_pattern_provider.json"));
        var wirelessModel = compact(read("src/main/resources/assets/ae2ltpp/models/block/wireless_packaged_pattern_provider.json"));

        assertTrue(packagedModel.contains("\"parent\":\"minecraft:block/cube_all\""));
        assertTrue(packagedModel.contains("\"all\":\"" + TEXTURE + "\""));
        assertTrue(wirelessModel.contains("\"parent\":\"ae2ltpp:block/packaged_pattern_provider\""));
    }

    @Test
    void packagedProviderTextureExistsAtBlockTextureResolution() throws IOException {
        var texture = Path.of("src/main/resources/assets/ae2ltpp/textures/block/overload_packaged_pattern_provider.png");

        assertTrue(Files.exists(texture), texture.toString());
        try (var in = Files.newInputStream(texture)) {
            var image = ImageIO.read(in);
            assertNotNull(image);
            assertEquals(16, image.getWidth());
            assertEquals(16, image.getHeight());
        }
    }

    @Test
    void blocksUseFixedAllPushDirectionBase() throws IOException {
        var base = read("src/main/java/com/moakiee/ae2lt/packaged/block/FixedPushDirectionProviderBlock.java");
        var packaged = read("src/main/java/com/moakiee/ae2lt/packaged/block/PackagedPatternProviderBlock.java");
        var wireless = read("src/main/java/com/moakiee/ae2lt/packaged/block/WirelessPackagedPatternProviderBlock.java");

        assertTrue(base.contains("extends AEBaseEntityBlock<T>"));
        assertTrue(base.contains("void setSide("));
        assertTrue(base.contains("PushDirection.ALL"));
        assertTrue(packaged.contains("extends FixedPushDirectionProviderBlock<PackagedPatternProviderBlockEntity>"));
        assertTrue(wireless.contains("extends FixedPushDirectionProviderBlock<WirelessPackagedPatternProviderBlockEntity>"));
    }

    @Test
    void blockEntitiesIgnoreStoredDirectionalPushTargets() throws IOException {
        var source = read("src/main/java/com/moakiee/ae2lt/packaged/blockentity/PackagedPatternProviderBlockEntity.java");

        assertTrue(source.contains("EnumSet.allOf(Direction.class)"));
        assertTrue(source.contains("EnumSet.noneOf(Direction.class)"));
    }

    private static void assertAllDirectionsUseBaseModel(String blockName) throws IOException {
        var blockstate = compact(read("src/main/resources/assets/ae2ltpp/blockstates/" + blockName + ".json"));
        var baseModel = "\"model\":\"ae2ltpp:block/" + blockName + "\"";

        assertTrue(blockstate.contains("\"push_direction=all\":{" + baseModel + "}"));
        for (var direction : DIRECTIONS) {
            assertTrue(blockstate.contains("\"push_direction=" + direction + "\":{" + baseModel + "}"), direction);
        }
        assertFalse(blockstate.contains("_oriented"));
    }

    private static String read(String path) throws IOException {
        return Files.readString(Path.of(path));
    }

    private static String compact(String value) {
        return value.replaceAll("\\s+", "");
    }
}
