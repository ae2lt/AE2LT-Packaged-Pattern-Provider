package com.moakiee.ae2lt.packaged.patternprovider;

import org.jetbrains.annotations.Nullable;

import appeng.api.config.Actionable;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEKeyType;
import appeng.api.stacks.GenericStack;
import appeng.api.storage.AEKeyFilter;
import appeng.helpers.patternprovider.PatternProviderReturnInventory;

/**
 * Insert-only return inventory with unlimited amount per occupied key slot.
 */
public final class UnlimitedPatternProviderReturnInventory
        extends PatternProviderReturnInventory {

    private UnlimitedPatternProviderReturnInventory(Runnable listener) {
        super(listener);
    }

    static UnlimitedPatternProviderReturnInventory create(
            Runnable listener, @Nullable AEKeyFilter filter) {
        var inventory = new UnlimitedPatternProviderReturnInventory(listener);
        if (filter != null) {
            inventory.setFilter(filter);
        }
        return inventory;
    }

    @Override
    public long insert(int slot, AEKey what, long amount, Actionable mode) {
        if (what == null || amount <= 0 || !isAllowed(what)) {
            return 0;
        }
        for (int i = 0; i < size(); i++) {
            if (what.equals(getKey(i))) {
                if (mode == Actionable.MODULATE) {
                    long merged;
                    try {
                        merged = Math.addExact(getAmount(i), amount);
                    } catch (ArithmeticException overflow) {
                        merged = Long.MAX_VALUE;
                    }
                    setStack(i, new GenericStack(what, merged));
                }
                return amount;
            }
        }
        for (int i = 0; i < size(); i++) {
            if (getKey(i) == null) {
                if (mode == Actionable.MODULATE) {
                    setStack(i, new GenericStack(what, amount));
                }
                return amount;
            }
        }
        return 0;
    }

    @Override
    public long extract(int slot, AEKey what, long amount, Actionable mode) {
        return 0;
    }

    @Override
    public boolean canExtract() {
        return false;
    }

    @Override
    public long getMaxAmount(AEKey key) {
        return Long.MAX_VALUE;
    }

    @Override
    public long getCapacity(AEKeyType space) {
        return Long.MAX_VALUE;
    }
}
