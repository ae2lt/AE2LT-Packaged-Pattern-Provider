package com.moakiee.ae2lt.packaged.logic;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/**
 * Source-level regression for the real-dispatch power race.
 *
 * <p>AUTO extraction can charge return-output power after the initial push
 * affordability check. The production guard must therefore be between plan
 * creation and DispatchExecutor, where it can stop the input commit without
 * changing virtual dispatch behavior.
 */
class PackagedPatternProviderPowerCostRegressionTest {
    @Test
    void realDispatchRechecksInputCostAfterAutoHarvestBeforeCommit() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/com/moakiee/ae2lt/packaged/logic/PackagedPatternProviderLogic.java"));

        int realPush = source.indexOf("private boolean tryRealDispatch(");
        int realEnd = source.indexOf("\n    // ===== Tick =====", realPush);
        assertTrue(realPush >= 0 && realEnd > realPush, "real dispatch method must exist");
        String method = source.substring(realPush, realEnd);

        int harvest = method.indexOf("candidate.adapter().extractOutputs(");
        int plan = method.indexOf("candidate.adapter().planWithBinding(");
        int recheck = method.indexOf(
                "if (plan != null && !PatternProviderPowerCost.canAfford(grid, inputCost)) {");
        int execute = method.indexOf("DispatchExecutor.execute(");

        assertTrue(harvest >= 0, "real AUTO path must harvest outputs");
        assertTrue(plan > harvest, "input plan must be built after pre-dispatch harvest");
        assertTrue(recheck > plan, "input affordability must be checked after planning");
        assertTrue(execute > recheck, "input affordability must be checked before commit");
        assertTrue(method.substring(recheck, execute).contains("return false;"),
                "unaffordable input dispatch must stop without committing inputs");
        assertTrue(source.contains(
                "tryRealDispatch(sl, patternDetails, inputHolder, binding, grid, cost, gameTick)"),
                "push path must pass the original grid and input cost to real dispatch");
    }

    @Test
    void inputPowerIsConsumedOnlyAfterSuccessfulRealDispatch() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/com/moakiee/ae2lt/packaged/logic/PackagedPatternProviderLogic.java"));

        int realCall = source.indexOf(
                "if (tryRealDispatch(sl, patternDetails, inputHolder, binding, grid, cost, gameTick)) {");
        int realCharge = source.indexOf("PatternProviderPowerCost.consumeRaw(grid, cost);", realCall);
        int realReturn = source.indexOf("return true;", realCall);
        assertTrue(realCall >= 0, "push path must call real dispatch");
        assertTrue(realCharge > realCall && realReturn > realCharge,
                "real input power must be consumed only after dispatch succeeds");

        int realPush = source.indexOf("private boolean tryRealDispatch(");
        int realEnd = source.indexOf("\n    // ===== Tick =====", realPush);
        assertTrue(realPush >= 0 && realEnd > realPush, "real dispatch method must exist");
        assertTrue(!source.substring(realPush, realEnd).contains("consumeRaw("),
                "real dispatch must not consume input power before push success is reported");
    }

    @Test
    void virtualDispatchStillUsesItsOriginalSingleAffordabilityGate() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/com/moakiee/ae2lt/packaged/logic/PackagedPatternProviderLogic.java"));

        int virtualPush = source.indexOf("private boolean tryVirtualPush(");
        int virtualEnd = source.indexOf("private boolean tryRealDispatch(", virtualPush);
        assertTrue(virtualPush >= 0 && virtualEnd > virtualPush, "virtual dispatch method must exist");
        assertTrue(!source.substring(virtualPush, virtualEnd).contains("canAfford("),
                "virtual dispatch must not gain a second affordability check");
        assertTrue(source.contains(
                "if (tryVirtualPush(sl, patternDetails, inputHolder, binding, gameTick)) {"));
        assertTrue(source.contains("PatternProviderPowerCost.consumeRaw(grid, cost);"));
        assertTrue(source.contains("if (tryRealDispatch(sl, patternDetails, inputHolder, binding, grid, cost, gameTick)) {"));
    }
}
