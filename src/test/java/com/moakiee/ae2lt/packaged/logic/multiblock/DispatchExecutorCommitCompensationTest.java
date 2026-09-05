package com.moakiee.ae2lt.packaged.logic.multiblock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import appeng.api.config.Actionable;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEKeyType;
import appeng.api.stacks.GenericStack;
import appeng.helpers.patternprovider.PatternProviderReturnInventory;

class DispatchExecutorCommitCompensationTest {
    @Test
    void simulationFailureIsReportedWithoutModulatingInput() {
        var key = new TestKey();
        var modulated = new AtomicLong();
        var target = new TargetSlot(
                null,
                BlockPos.ZERO,
                null,
                List.of(new GenericStack(key, 1)),
                InsertionStrategy.CUSTOM,
                (inserted, mode) -> {
                    if (mode == Actionable.SIMULATE) {
                        throw new IllegalStateException("simulation unavailable");
                    }
                    modulated.incrementAndGet();
                    return inserted.amount();
                });
        var returnInventory = new PatternProviderReturnInventory(() -> {
        });

        var result = DispatchExecutor.execute(
                new DispatchPlan(List.of(target), null), null, returnInventory);

        assertTrue(result.failure());
        assertEquals(0, modulated.get());
        assertEquals(0, returnInventory.getAmount(0));
    }

    @Test
    void fullyRecoveredCommitFailureReturnsOwnershipToCpuWithoutRetention() {
        var key = new TestKey();
        var firstAccepted = new AtomicLong();
        var secondAccepted = new AtomicLong();
        var first = target(key, 1, firstAccepted);
        var second = target(key, 2, secondAccepted);
        var compensated = new ArrayList<AcceptedInsertion>();
        var returnInventory = new PatternProviderReturnInventory(() -> {
        });

        var plan = new DispatchPlan(
                List.of(first, second),
                () -> {
                    throw new DispatchCommitException("activation remained inactive");
                },
                (accepted, recovered) -> {
                    compensated.addAll(accepted);
                    for (var insertion : accepted) {
                        var targetAmount = insertion.target() == first
                                ? firstAccepted
                                : secondAccepted;
                        long amount = insertion.stack().amount();
                        assertTrue(targetAmount.compareAndSet(amount, 0));
                        recovered.accept(insertion.stack());
                    }
                });

        var residuals = new ArrayList<GenericStack>();
        var result = DispatchExecutor.execute(plan, null, returnInventory, residuals::add);

        assertTrue(result.failure());
        assertEquals(0, firstAccepted.get());
        assertEquals(0, secondAccepted.get());
        assertEquals(2, compensated.size());
        assertEquals(0, returnInventory.getAmount(0));
        assertTrue(residuals.isEmpty());
    }

    @Test
    void confirmedRecoverySurvivesLaterCompensationFailure() {
        var key = new TestKey();
        var firstAccepted = new AtomicLong();
        var secondAccepted = new AtomicLong();
        var first = target(key, 1, firstAccepted);
        var second = target(key, 2, secondAccepted);
        var returnInventory = new PatternProviderReturnInventory(() -> {
        });

        var plan = new DispatchPlan(
                List.of(first, second),
                () -> {
                    throw new DispatchCommitException("activation remained inactive");
                },
                (accepted, recovered) -> {
                    var insertion = accepted.get(0);
                    assertTrue(firstAccepted.compareAndSet(insertion.stack().amount(), 0));
                    recovered.accept(insertion.stack());
                    assertEquals(0, returnInventory.getAmount(0));
                    throw new IllegalStateException("second recovery failed");
                });

        var result = DispatchExecutor.execute(plan, null, returnInventory);

        assertTrue(result.success());
        assertEquals(0, firstAccepted.get());
        assertEquals(2, secondAccepted.get());
        assertEquals(1, returnInventory.getAmount(0));
        assertEquals(key, returnInventory.getKey(0));
    }

    @Test
    void partialRecoveryKeepsSuccessAndDoesNotRefundTargetOwnedInputs() {
        var key = new TestKey();
        var targetAmount = new AtomicLong();
        var returnInventory = new PatternProviderReturnInventory(() -> {
        });
        var residuals = new ArrayList<GenericStack>();
        var plan = new DispatchPlan(
                List.of(target(key, 3, targetAmount)),
                () -> {
                    throw new DispatchCommitException("activation remained inactive");
                },
                (accepted, recovered) -> {
                    targetAmount.decrementAndGet();
                    recovered.accept(new GenericStack(key, 1));
                });

        var result = DispatchExecutor.execute(plan, null, returnInventory, residuals::add);

        assertTrue(result.success());
        assertEquals(2, targetAmount.get());
        assertEquals(1, returnInventory.getAmount(0));
        assertEquals(key, returnInventory.getKey(0));
        assertTrue(residuals.isEmpty());
        assertEquals(3, targetAmount.get() + returnInventory.getAmount(0));
    }

    @Test
    void recoveryMustCoverEveryAcceptedKeyNotJustTheTotalAmount() {
        var firstKey = new TestKey();
        var secondKey = new TestKey();
        var firstAmount = new AtomicLong();
        var secondAmount = new AtomicLong();
        var returnInventory = new PatternProviderReturnInventory(() -> {
        });
        var plan = new DispatchPlan(
                List.of(target(firstKey, 1, firstAmount), target(secondKey, 2, secondAmount)),
                () -> {
                    throw new DispatchCommitException("activation remained inactive");
                },
                (accepted, recovered) -> {
                    firstAmount.set(0);
                    recovered.accept(new GenericStack(firstKey, 3));
                });

        var result = DispatchExecutor.execute(plan, null, returnInventory);

        assertTrue(result.success());
        assertEquals(0, firstAmount.get());
        assertEquals(2, secondAmount.get());
        assertEquals(1, returnInventory.getAmount(0));
        assertEquals(firstKey, returnInventory.getKey(0));
    }

    @Test
    void fullRecoveryFollowedByExceptionStillRetainsInputsAndReportsSuccess() {
        var key = new TestKey();
        var targetAmount = new AtomicLong();
        var returnInventory = new PatternProviderReturnInventory(() -> {
        });
        var plan = new DispatchPlan(
                List.of(target(key, 3, targetAmount)),
                () -> {
                    throw new DispatchCommitException("activation remained inactive");
                },
                (accepted, recovered) -> {
                    targetAmount.addAndGet(-1);
                    recovered.accept(new GenericStack(key, 1));
                    targetAmount.addAndGet(-2);
                    recovered.accept(new GenericStack(key, 2));
                    assertEquals(0, returnInventory.getAmount(0));
                    throw new IllegalStateException("cleanup failed after recovery");
                });

        var result = DispatchExecutor.execute(plan, null, returnInventory);

        assertTrue(result.success());
        assertEquals(0, targetAmount.get());
        assertEquals(3, returnInventory.getAmount(0));
        assertEquals(key, returnInventory.getKey(0));
    }

    @Test
    void missingCompensationCannotRefundInputsStillOwnedByTarget() {
        var key = new TestKey();
        var targetAmount = new AtomicLong();
        var returnInventory = new PatternProviderReturnInventory(() -> {
        });
        var residuals = new ArrayList<GenericStack>();
        var plan = new DispatchPlan(List.of(target(key, 3, targetAmount)), () -> {
            throw new DispatchCommitException("activation remained inactive");
        });

        var result = DispatchExecutor.execute(plan, null, returnInventory, residuals::add);

        assertTrue(result.success());
        assertEquals(3, targetAmount.get());
        assertEquals(0, returnInventory.getAmount(0));
        assertTrue(residuals.isEmpty());
    }

    @Test
    void partialInsertionRetainsConfirmedRecoveryAndUndeliveredAmountsExactlyOnce() {
        var key = new TestKey();
        var targetAmount = new AtomicLong();
        var commitCalls = new AtomicLong();
        var residuals = new ArrayList<GenericStack>();
        var returnInventory = new PatternProviderReturnInventory(() -> {
        }) {
            @Override
            public long insert(int slot, AEKey what, long amount, Actionable mode) {
                return super.insert(slot, what, Math.min(1, amount), mode);
            }
        };
        var target = new TargetSlot(null, BlockPos.ZERO, null,
                List.of(new GenericStack(key, 5), new GenericStack(key, 4)),
                InsertionStrategy.CUSTOM, (stack, mode) -> {
                    if (mode == Actionable.SIMULATE) {
                        return stack.amount();
                    }
                    targetAmount.addAndGet(3);
                    return 3L;
                });
        var plan = new DispatchPlan(List.of(target), commitCalls::incrementAndGet,
                (accepted, recovered) -> {
                    targetAmount.addAndGet(-2);
                    recovered.accept(new GenericStack(key, 1));
                    recovered.accept(new GenericStack(key, 1));
                });

        var result = DispatchExecutor.execute(plan, null, returnInventory, residuals::add);

        assertTrue(result.success());
        assertEquals(0, commitCalls.get());
        assertEquals(1, targetAmount.get());
        assertEquals(3, returnInventory.getAmount(0));
        assertEquals(List.of(new GenericStack(key, 1), new GenericStack(key, 1),
                new GenericStack(key, 3)), residuals);
        assertEquals(9, targetAmount.get() + returnInventory.getAmount(0)
                + residuals.stream().mapToLong(GenericStack::amount).sum());
    }

    @Test
    void firstThrowingInsertionCannotRefundOrRecoverItsUnknownSideEffects() {
        assertFirstThrowingInsertion(1);
        assertFirstThrowingInsertion(2);
    }

    private void assertFirstThrowingInsertion(long sideEffectAmount) {
        var key = new TestKey();
        var unknownAmount = new AtomicLong();
        var unattemptedAmount = new AtomicLong();
        var commitCalls = new AtomicLong();
        var compensationCalls = new AtomicLong();
        var residuals = new ArrayList<GenericStack>();
        var returnInventory = new PatternProviderReturnInventory(() -> {
        }) {
            @Override
            public long insert(int slot, AEKey what, long amount, Actionable mode) {
                return 0L;
            }
        };
        var throwingTarget = new TargetSlot(null, BlockPos.ZERO, null,
                List.of(new GenericStack(key, 2), new GenericStack(key, 3)),
                InsertionStrategy.CUSTOM, (stack, mode) -> {
                    if (mode == Actionable.MODULATE) {
                        unknownAmount.addAndGet(sideEffectAmount);
                        throw new IllegalStateException("insertion threw after side effects");
                    }
                    return stack.amount();
                });
        var plan = new DispatchPlan(
                List.of(throwingTarget, target(key, 4, unattemptedAmount)),
                commitCalls::incrementAndGet,
                (accepted, recovered) -> compensationCalls.incrementAndGet());

        var result = DispatchExecutor.execute(plan, null, returnInventory, residuals::add);

        assertTrue(result.success());
        assertEquals(sideEffectAmount, unknownAmount.get());
        assertEquals(0, unattemptedAmount.get());
        assertEquals(0, commitCalls.get());
        assertEquals(0, compensationCalls.get());
        assertEquals(0, returnInventory.getAmount(0));
        assertEquals(List.of(new GenericStack(key, 3), new GenericStack(key, 4)), residuals);
    }

    @Test
    void modulationExceptionCannotRefundUnknownStackEvenWhenPriorAcceptedInputsFullyRecover() {
        var key = new TestKey();
        var targetAmount = new AtomicLong();
        var unknownAmount = new AtomicLong();
        var unattemptedAmount = new AtomicLong();
        var residuals = new ArrayList<GenericStack>();
        var returnInventory = new PatternProviderReturnInventory(() -> {
        });
        var throwingTarget = new TargetSlot(null, BlockPos.ZERO, null,
                List.of(new GenericStack(key, 2)), InsertionStrategy.CUSTOM,
                (stack, mode) -> {
                    if (mode == Actionable.MODULATE) {
                        unknownAmount.addAndGet(stack.amount());
                        throw new IllegalStateException("insertion threw after side effects");
                    }
                    return stack.amount();
                });
        var firstTarget = target(key, 1, targetAmount);
        var plan = new DispatchPlan(
                List.of(firstTarget, throwingTarget, target(key, 4, unattemptedAmount)), null,
                (accepted, recovered) -> {
                    assertEquals(List.of(new AcceptedInsertion(firstTarget, new GenericStack(key, 1))), accepted);
                    targetAmount.set(0);
                    accepted.forEach(insertion -> recovered.accept(insertion.stack()));
                });

        var result = DispatchExecutor.execute(plan, null, returnInventory, residuals::add);

        assertTrue(result.success());
        assertEquals(0, targetAmount.get());
        assertEquals(2, unknownAmount.get());
        assertEquals(0, unattemptedAmount.get());
        assertEquals(5, returnInventory.getAmount(0));
        assertTrue(residuals.isEmpty());
        assertEquals(7, unknownAmount.get() + returnInventory.getAmount(0));
    }

    @Test
    void firstConfirmedRejectionStillReturnsOwnershipWithoutRetention() {
        var key = new TestKey();
        var unattemptedAmount = new AtomicLong();
        var residuals = new ArrayList<GenericStack>();
        var returnInventory = new PatternProviderReturnInventory(() -> {
        });
        var rejectingTarget = new TargetSlot(null, BlockPos.ZERO, null,
                List.of(new GenericStack(key, 2)), InsertionStrategy.CUSTOM,
                (stack, mode) -> mode == Actionable.SIMULATE ? stack.amount() : 0L);
        var plan = new DispatchPlan(
                List.of(rejectingTarget, target(key, 4, unattemptedAmount)), null);

        var result = DispatchExecutor.execute(plan, null, returnInventory, residuals::add);

        assertTrue(result.failure());
        assertEquals(0, unattemptedAmount.get());
        assertEquals(0, returnInventory.getAmount(0));
        assertTrue(residuals.isEmpty());
    }

    private static TargetSlot target(AEKey key, long amount, AtomicLong acceptedAmount) {
        var stack = new GenericStack(key, amount);
        return new TargetSlot(
                null,
                BlockPos.ZERO,
                null,
                List.of(stack),
                InsertionStrategy.CUSTOM,
                (inserted, mode) -> {
                    if (!inserted.what().equals(key) || inserted.amount() != amount) {
                        return 0L;
                    }
                    if (mode == Actionable.MODULATE) {
                        acceptedAmount.addAndGet(amount);
                    }
                    return amount;
                });
    }

    private static final class TestKey extends AEKey {
        private static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("test", "key");

        @Override
        public AEKeyType getType() {
            return null;
        }

        @Override
        public AEKey dropSecondary() {
            return this;
        }

        @Override
        public CompoundTag toTag() {
            return new CompoundTag();
        }

        @Override
        public Object getPrimaryKey() {
            return ID;
        }

        @Override
        public ResourceLocation getId() {
            return ID;
        }

        @Override
        public void writeToPacket(FriendlyByteBuf buffer) {
        }

        @Override
        protected Component computeDisplayName() {
            return Component.literal("test key");
        }

        @Override
        public void addDrops(long amount, List<ItemStack> drops, Level level, BlockPos pos) {
        }

        @Override
        public boolean isTagged(TagKey<?> tag) {
            return false;
        }
    }
}
