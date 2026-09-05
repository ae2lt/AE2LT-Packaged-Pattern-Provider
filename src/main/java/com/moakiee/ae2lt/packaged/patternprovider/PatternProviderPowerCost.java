package com.moakiee.ae2lt.packaged.patternprovider;

import org.jetbrains.annotations.Nullable;

import appeng.api.config.Actionable;
import appeng.api.config.PowerMultiplier;
import appeng.api.networking.IGrid;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;

/**
 * Legacy AE2LT provider power model retained by PP's provider implementation.
 */
public final class PatternProviderPowerCost {
    private static final double AE_PER_OPERATION = 1.0;
    private static final double RESERVE_TICKS = 1.0;
    private static final long ITEMS_PER_OPERATION = 4L;
    private static final long FLUID_PER_OPERATION = 500L;

    private PatternProviderPowerCost() {
    }

    private static double idleReserveForIdlePowerUsage(double idlePowerUsage) {
        return Math.max(0.0, idlePowerUsage) * RESERVE_TICKS;
    }

    private static double cost(AEKey key, long amount) {
        if (key == null || amount <= 0) {
            return 0.0;
        }
        return ceilDiv(amount, amountPerOperation(key)) * AE_PER_OPERATION;
    }

    /**
     * Ceiling division for non-negative dividends and positive divisors.
     *
     * <p>{@code Math.ceilDiv} is Java 18+, and this branch compiles at Java 17
     * to match Forge 1.20.1.
     */
    private static long ceilDiv(long dividend, long divisor) {
        if (dividend < 0 || divisor <= 0) {
            throw new IllegalArgumentException("ceilDiv requires a non-negative dividend and positive divisor");
        }
        return dividend / divisor + (dividend % divisor == 0 ? 0 : 1);
    }

    public static double totalCost(KeyCounter[] inputs) {
        if (inputs == null) {
            return 0.0;
        }
        double total = 0.0;
        for (var counter : inputs) {
            if (counter == null) {
                continue;
            }
            for (var entry : counter) {
                total += cost(entry.getKey(), entry.getLongValue());
            }
        }
        return total;
    }

    public static long maxAffordable(@Nullable IGrid grid, AEKey key, long requested) {
        if (grid == null || key == null || requested <= 0) {
            return 0;
        }
        double need = cost(key, requested);
        if (need <= 0.0) {
            return requested;
        }

        var energy = grid.getEnergyService();
        double reserve = idleReserveForIdlePowerUsage(energy.getIdlePowerUsage());
        double available = energy.extractAEPower(
                need + reserve, Actionable.SIMULATE, PowerMultiplier.CONFIG);
        double usable = available - reserve;
        if (usable + 1.0e-6 >= need) {
            return requested;
        }

        long affordableOperations = (long) Math.floor(usable / AE_PER_OPERATION);
        if (affordableOperations <= 0) {
            return 0;
        }
        return amountForOperations(requested, amountPerOperation(key), affordableOperations);
    }

    public static void consume(@Nullable IGrid grid, AEKey key, long amount) {
        if (grid == null || key == null || amount <= 0) {
            return;
        }
        double need = cost(key, amount);
        if (need > 0.0) {
            grid.getEnergyService().extractAEPower(
                    need, Actionable.MODULATE, PowerMultiplier.CONFIG);
        }
    }

    public static boolean canAfford(@Nullable IGrid grid, double need) {
        if (need <= 0.0) {
            return true;
        }
        if (grid == null) {
            return false;
        }
        var energy = grid.getEnergyService();
        double total = need + idleReserveForIdlePowerUsage(energy.getIdlePowerUsage());
        double available = energy.extractAEPower(
                total, Actionable.SIMULATE, PowerMultiplier.CONFIG);
        return available + 1.0e-6 >= total;
    }

    public static void consumeRaw(@Nullable IGrid grid, double need) {
        if (grid != null && need > 0.0) {
            grid.getEnergyService().extractAEPower(
                    need, Actionable.MODULATE, PowerMultiplier.CONFIG);
        }
    }

    private static long amountPerOperation(AEKey key) {
        if (AEItemKey.is(key)) {
            return ITEMS_PER_OPERATION;
        }
        if (AEFluidKey.is(key)) {
            return FLUID_PER_OPERATION;
        }
        return Math.max(1L, key.getAmountPerOperation());
    }

    private static long amountForOperations(
            long requested, long amountPerOperation, long operations) {
        if (requested <= 0 || operations <= 0) {
            return 0;
        }
        long affordable;
        try {
            affordable = Math.multiplyExact(operations, Math.max(1L, amountPerOperation));
        } catch (ArithmeticException ignored) {
            affordable = Long.MAX_VALUE;
        }
        return Math.min(requested, affordable);
    }
}
