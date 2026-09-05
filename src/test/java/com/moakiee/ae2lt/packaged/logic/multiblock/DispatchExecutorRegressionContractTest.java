package com.moakiee.ae2lt.packaged.logic.multiblock;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class DispatchExecutorRegressionContractTest {
    @Test
    void unconfirmedRollbackCannotReturnFailure() throws IOException {
        var source = read(
                "src/main/java/com/moakiee/ae2lt/packaged/logic/multiblock/DispatchExecutor.java");

        assertTrue(source.contains("Without a confirmed full rollback of a failed commit"));
        assertTrue(source.contains("commitFailed && !modulationFailed && !compensationFailed"));
        assertTrue(source.contains("acceptedByKey.equals(recoveredByKey)"));
        assertTrue(source.contains("return DispatchResult.success(null);"));
        assertTrue(source.contains("Predicate<GenericStack> residualSink"));
        assertTrue(source.contains("new AcceptedInsertion"));
        assertTrue(source.contains("List.copyOf(acceptedInsertions)"));
        assertTrue(source.contains("Math.max(0L, Math.min("));
    }

    private static String read(String path) throws IOException {
        return Files.readString(Path.of(path));
    }
}
