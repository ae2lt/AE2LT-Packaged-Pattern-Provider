package com.moakiee.ae2lt.packaged.logic.multiblock.binding;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class VirtualBatchAccumulatorNbtTest {
    @Test
    void declaresNbtRoundTripAndFailureRecovery() throws IOException {
        var source = Files.readString(Path.of(
                "src/main/java/com/moakiee/ae2lt/packaged/logic/multiblock/binding/"
                        + "VirtualBatchAccumulator.java"));
        assertTrue(source.contains("writeToNBT(CompoundTag tag)"));
        assertTrue(source.contains("readFromNBT(CompoundTag tag)"));
        assertTrue(source.contains("mergePending(stack.what(), stack.amount())"));
        assertTrue(source.contains("Math.addExact(previous, amount)"));
        assertTrue(source.contains("Math.subtractExact(gameTick, lastFlushTick)"));
        assertTrue(source.contains("elapsed = Long.MAX_VALUE"));
        assertTrue(source.contains("Virtual batch flush failed; retaining"));
    }
}
