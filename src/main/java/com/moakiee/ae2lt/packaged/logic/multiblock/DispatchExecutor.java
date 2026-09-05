package com.moakiee.ae2lt.packaged.logic.multiblock;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.GenericStack;
import appeng.helpers.patternprovider.PatternProviderReturnInventory;
import appeng.helpers.patternprovider.PatternProviderTarget;

import com.moakiee.ae2lt.packaged.logic.DispatchResult;

public final class DispatchExecutor {

    private static final Logger LOG = LoggerFactory.getLogger(DispatchExecutor.class);

    private DispatchExecutor() {
    }

    public static DispatchResult<Void> execute(DispatchPlan plan,
                                               IActionSource source,
                                               PatternProviderReturnInventory returnInv) {
        return execute(plan, source, returnInv, residual -> false);
    }

    public static DispatchResult<Void> execute(DispatchPlan plan,
                                               IActionSource source,
                                               PatternProviderReturnInventory returnInv,
                                               Predicate<GenericStack> residualSink) {
        if (plan == null || returnInv == null || residualSink == null) {
            return DispatchResult.failure("Dispatch plan or retention sink was unavailable.");
        }
        var targets = plan.targets();
        if (targets == null || targets.isEmpty()) {
            LOG.debug("Dispatch skipped: plan contained no insertion targets.");
            return DispatchResult.failure("Dispatch plan contained no insertion targets.");
        }

        for (var target : targets) {
            if (target == null || target.stacks() == null || target.stacks().isEmpty()) {
                return DispatchResult.failure("Dispatch plan contained an invalid insertion target.");
            }
            DispatchResult<Void> simulation;
            try {
                simulation = simulateTarget(target, source);
            } catch (RuntimeException | LinkageError e) {
                LOG.warn("Dispatch simulation failed at {}; refusing to modulate input",
                        target == null ? "<null>" : target.pos(), e);
                return DispatchResult.failure("Target simulation failed before input was committed.");
            }
            if (simulation.failure()) {
                return simulation;
            }
        }

        boolean acceptedAny = false;
        boolean partial = false;
        boolean modulationFailed = false;
        var acceptedInsertions = new ArrayList<AcceptedInsertion>();
        var undelivered = new ArrayList<GenericStack>();
        for (int targetIndex = 0; targetIndex < targets.size(); targetIndex++) {
            var target = targets.get(targetIndex);
            for (int stackIndex = 0; stackIndex < target.stacks().size(); stackIndex++) {
                var stack = target.stacks().get(stackIndex);
                long accepted;
                try {
                    accepted = Math.max(0L, Math.min(
                            stack.amount(),
                            insertOne(target, stack, Actionable.MODULATE, source)));
                } catch (RuntimeException | LinkageError e) {
                    modulationFailed = true;
                    partial = true;
                    LOG.warn("Dispatch modulation failed at {}; ownership of {} x{} is unknown; compensating only prior confirmed insertions",
                            target.pos(), stack.what(), stack.amount(), e);
                    // The throwing call may already have inserted some or all of this
                    // stack. Only inputs not attempted after it are known undelivered.
                    addRemainingResiduals(undelivered, targets, targetIndex, stackIndex + 1);
                    break;
                }
                if (accepted > 0) {
                    acceptedAny = true;
                    acceptedInsertions.add(new AcceptedInsertion(
                            target,
                            new GenericStack(stack.what(), accepted)));
                }
                if (accepted < stack.amount()) {
                    partial = true;
                    addResidual(undelivered, stack.what(), stack.amount() - accepted);
                    addRemainingResiduals(undelivered, targets, targetIndex, stackIndex + 1);
                    break;
                }
            }
            if (partial) {
                break;
            }
        }

        boolean commitFailed = modulationFailed;
        if (!partial) {
            try {
                if (plan.onCommit() != null) {
                    plan.onCommit().run();
                }
                return DispatchResult.success(null);
            } catch (RuntimeException | LinkageError e) {
                commitFailed = true;
                LOG.warn("Dispatch commit was not confirmed; attempting exact compensation: {}",
                        e.toString());
            }
        }

        // Only a confirmed rejection leaves every input with the caller. A
        // throwing first insertion has unknown effects and cannot allow a refund.
        if (!acceptedAny && !modulationFailed) {
            return DispatchResult.failure("Target rejected input during modulation.");
        }

        var acceptedByKey = new java.util.LinkedHashMap<appeng.api.stacks.AEKey, Long>();
        for (var insertion : acceptedInsertions) {
            addAmount(acceptedByKey, insertion.stack().what(), insertion.stack().amount());
        }
        var recoveredByKey = new java.util.LinkedHashMap<appeng.api.stacks.AEKey, Long>();
        boolean compensationFailed = false;
        if (acceptedAny && plan.onPartialCommit() != null) {
            try {
                plan.onPartialCommit().recover(List.copyOf(acceptedInsertions), stack -> {
                    if (stack == null || stack.what() == null || stack.amount() <= 0) {
                        return;
                    }
                    long accepted = acceptedByKey.getOrDefault(stack.what(), 0L);
                    long alreadyRecovered = recoveredByKey.getOrDefault(stack.what(), 0L);
                    long remaining = accepted - Math.min(accepted, alreadyRecovered);
                    long recoverable = Math.min(stack.amount(), Math.max(0L, remaining));
                    if (recoverable > 0) {
                        // Record each confirmation before recovery can fail on a later stack.
                        addAmount(recoveredByKey, stack.what(), recoverable);
                    }
                });
            } catch (RuntimeException | LinkageError e) {
                compensationFailed = true;
                LOG.warn("Dispatch compensation failed; retaining accepted inputs", e);
            }
        } else if (acceptedAny) {
            compensationFailed = true;
            LOG.warn("Dispatch compensation unavailable; retaining accepted inputs");
        }

        // AE2 retains the complete input holder. Only a confirmed full rollback
        // of a failed commit may hand ownership back without also retaining inputs.
        // A throwing modulation has unknown effects, even if prior insertions recover.
        if (commitFailed && !modulationFailed && !compensationFailed
                && acceptedByKey.equals(recoveredByKey)) {
            return DispatchResult.failure("Dispatch commit failed; all accepted inputs were recovered.");
        }

        for (var entry : recoveredByKey.entrySet()) {
            routeRecovered(new GenericStack(entry.getKey(), entry.getValue()), returnInv, residualSink);
        }

        // Do not synthesize recovery for amounts the callback did not confirm
        // removing. Those stacks may still be in the target; returning them here
        // would duplicate inputs. Keep the target-owned remainder in place and
        // make the uncertainty visible in the log instead.
        if (commitFailed) {
            for (var entry : acceptedByKey.entrySet()) {
                long recovered = recoveredByKey.getOrDefault(entry.getKey(), 0L);
                long missing = entry.getValue() - Math.min(entry.getValue(), recovered);
                if (missing > 0) {
                    LOG.warn("Dispatch compensation could not confirm {} x{} for {}",
                            entry.getKey(), missing, entry.getKey());
                }
            }
        }

        for (var stack : undelivered) {
            routeRecovered(stack, returnInv, residualSink);
        }
        if (commitFailed) {
            LOG.warn("Dispatch commit or modulation failed; {} recovered key(s) and {} undelivered stack(s) were routed to retention",
                    recoveredByKey.size(), undelivered.size());
        } else {
            LOG.warn("Dispatch race: {} input stack(s) were partially committed; all residuals were routed to retention",
                    undelivered.size());
        }
        // Without a confirmed full rollback of a failed commit, report success.
        // Returning failure would refund AE2's complete input holder and duplicate
        // inputs still owned by targets or already routed to retention.
        return DispatchResult.success(null);
    }

    private static void routeRecovered(GenericStack stack,
                                       PatternProviderReturnInventory returnInv,
                                       Predicate<GenericStack> residualSink) {
        if (stack == null || stack.what() == null || stack.amount() <= 0) {
            return;
        }
        long inserted;
        try {
            inserted = Math.max(0L, Math.min(stack.amount(),
                    returnInv.insert(0, stack.what(), stack.amount(), Actionable.MODULATE)));
        } catch (RuntimeException | LinkageError e) {
            inserted = 0L;
            LOG.warn("Dispatch compensation return-inventory insertion failed for {} x{}",
                    stack.what(), stack.amount(), e);
        }
        long residual = stack.amount() - inserted;
        if (residual <= 0) {
            return;
        }
        try {
            if (!residualSink.test(new GenericStack(stack.what(), residual))) {
                LOG.warn("Dispatch residual sink rejected {} x{} after compensation",
                        stack.what(), residual);
            }
        } catch (RuntimeException | LinkageError e) {
            LOG.warn("Dispatch residual sink failed for {} x{} after compensation",
                    stack.what(), residual, e);
        }
    }

    private static void addResidual(List<GenericStack> residuals, appeng.api.stacks.AEKey what, long amount) {
        if (what == null || amount <= 0) {
            return;
        }
        residuals.add(new GenericStack(what, amount));
    }

    private static void addRemainingResiduals(List<GenericStack> residuals,
                                              List<TargetSlot> targets,
                                              int targetIndex,
                                              int firstStackIndex) {
        if (targetIndex < 0 || targetIndex >= targets.size()) {
            return;
        }
        var target = targets.get(targetIndex);
        for (int i = firstStackIndex; i < target.stacks().size(); i++) {
            var remaining = target.stacks().get(i);
            addResidual(residuals, remaining.what(), remaining.amount());
        }
        for (int i = targetIndex + 1; i < targets.size(); i++) {
            for (var remaining : targets.get(i).stacks()) {
                addResidual(residuals, remaining.what(), remaining.amount());
            }
        }
    }

    private static void addAmount(
            java.util.Map<appeng.api.stacks.AEKey, Long> amounts,
            appeng.api.stacks.AEKey key,
            long amount) {
        if (key == null || amount <= 0) {
            return;
        }
        var previous = amounts.get(key);
        long merged;
        try {
            merged = previous == null ? amount : Math.addExact(previous, amount);
        } catch (ArithmeticException overflow) {
            merged = Long.MAX_VALUE;
        }
        amounts.put(key, merged);
    }

    private static DispatchResult<Void> simulateTarget(TargetSlot target, IActionSource source) {
        for (var stack : target.stacks()) {
            long accepted = insertOne(target, stack, Actionable.SIMULATE, source);
            if (accepted < stack.amount()) {
                LOG.debug("Dispatch simulation rejected at {}: {} x{} (only {} would fit)",
                        target.pos(), stack.what(), stack.amount(), accepted);
                return DispatchResult.failure(
                        "Target at " + target.pos() + " rejected "
                                + stack.what() + " x" + stack.amount() + " during simulation.");
            }
        }
        return DispatchResult.success(null);
    }

    private static long insertOne(TargetSlot target, GenericStack stack,
                                  Actionable mode, IActionSource source) {
        return switch (target.strategy()) {
            case STANDARD -> {
                var be = target.level().getBlockEntity(target.pos());
                if (be == null) {
                    yield 0L;
                }
                var ppt = PatternProviderTarget.get(target.level(), target.pos(), be, target.face(), source);
                if (ppt == null) {
                    yield 0L;
                }
                yield ppt.insert(stack.what(), stack.amount(), mode);
            }
            case CUSTOM -> {
                if (target.customInserter() == null) {
                    yield 0L;
                }
                yield target.customInserter().apply(stack, mode);
            }
        };
    }
}
