package com.moakiee.ae2lt.packaged.logic.multiblock;

import java.util.Objects;

import appeng.api.stacks.GenericStack;

public record AcceptedInsertion(TargetSlot target, GenericStack stack) {
    public AcceptedInsertion {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(stack, "stack");
        if (stack.amount() <= 0) {
            throw new IllegalArgumentException("Accepted insertion amount must be positive");
        }
    }
}
