package com.moakiee.ae2lt.packaged.logic;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class PackagedPatternProviderTickerContractTest {
    @Test
    void autoModeRemainsScheduledAfterACompletedPoll() throws IOException {
        var source = read(
                "src/main/java/com/moakiee/ae2lt/packaged/logic/PackagedPatternProviderLogic.java");
        assertTrue(source.contains("return getProviderHost().getReturnMode() == ReturnMode.AUTO;"));
        assertTrue(source.contains("elapsedTicks(gameTick, lastAutoReturnTick) >= AUTO_RETURN_INTERVAL_TICKS"));
    }

    @Test
    void tickerKeepsAutoModeAtSlowerCadence() throws IOException {
        var source = read(
                "src/main/java/com/moakiee/ae2lt/packaged/patternprovider/StablePatternProviderLogic.java");
        assertTrue(source.contains("return TickRateModulation.SLOWER;"));
        assertTrue(source.contains("return hasAutoReturnWork() || !returnInventory.isEmpty();"));
    }

    @Test
    void pendingAdapterRetryDoesNotDependOnAutoReturnOrGridTicking() throws IOException {
        var blockEntity = read(
                "src/main/java/com/moakiee/ae2lt/packaged/blockentity/PackagedPatternProviderBlockEntity.java");
        var logic = read(
                "src/main/java/com/moakiee/ae2lt/packaged/logic/PackagedPatternProviderLogic.java");
        var adapter = read(
                "src/main/java/com/moakiee/ae2lt/packaged/logic/multiblock/malum/"
                        + "MalumSpiritFocusingAdapter.java");

        assertTrue(blockEntity.contains("packaged.tickPendingAdapters(level)"));
        assertTrue(logic.contains("PENDING_RETRY_INTERVAL_TICKS"));
        assertTrue(logic.contains("adapter.tickPending(level, pos, adapterScope())"));
        assertTrue(logic.contains("getValidConnections(providerLevel, gameTick)"));
        assertTrue(adapter.contains("public void tickPending"));
        assertTrue(adapter.contains("MalumDroppedItemOwnership.load"));
        assertTrue(adapter.contains("level, mainPos, ownership.recipeId()"));
    }

    private static String read(String path) throws IOException {
        return Files.readString(Path.of(path));
    }
}
