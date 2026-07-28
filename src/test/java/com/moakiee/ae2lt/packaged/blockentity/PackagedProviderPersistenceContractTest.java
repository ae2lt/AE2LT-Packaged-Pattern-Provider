package com.moakiee.ae2lt.packaged.blockentity;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class PackagedProviderPersistenceContractTest {
    @Test
    void keepsLegacyAddonAndFrequencyFieldsOnTheBlockEntityRoot() throws IOException {
        var source = read(
                "src/main/java/com/moakiee/ae2lt/packaged/blockentity/"
                        + "PackagedPatternProviderBlockEntity.java");

        assertTrue(source.contains("\"ae2ltpp_adapter_inv\""));
        assertTrue(source.contains("\"ae2ltpp_adapter_flags\""));
        assertTrue(source.contains("frequencyBinding.load(data)"));
        assertTrue(source.contains("frequencyBinding.save(data)"));
    }

    @Test
    void keepsLegacyMemoryCardsOnThePublicFrequencyBridge() throws IOException {
        var source = read(
                "src/main/java/com/moakiee/ae2lt/packaged/blockentity/"
                        + "PackagedPatternProviderBlockEntity.java");

        assertTrue(source.contains(
                "frequencyBinding.exportMemorySettings("));
        assertTrue(source.contains(
                "this::writeStableMemoryCardSettings"));
        assertTrue(source.contains(
                "frequencyBinding.importMemorySettings("));
        assertTrue(source.contains(
                "this::readStableMemoryCardSettings"));
    }

    @Test
    void keepsBlockAndBlockEntityRegistryIds() throws IOException {
        var blocks = read(
                "src/main/java/com/moakiee/ae2lt/packaged/registry/PPBlocks.java");
        var blockEntities = read(
                "src/main/java/com/moakiee/ae2lt/packaged/registry/PPBlockEntities.java");

        assertTrue(blocks.contains(
                "registerBlock(\"packaged_pattern_provider\""));
        assertTrue(blocks.contains(
                "registerBlock(\"wireless_packaged_pattern_provider\""));
        assertTrue(blockEntities.contains(
                "\"packaged_pattern_provider\""));
        assertTrue(blockEntities.contains(
                "\"wireless_packaged_pattern_provider\""));
    }

    private static String read(String path) throws IOException {
        return Files.readString(Path.of(path));
    }
}
