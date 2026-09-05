package com.moakiee.ae2lt.packaged.logic.multiblock;

import java.util.List;
import java.util.function.Consumer;

import appeng.api.stacks.GenericStack;

@FunctionalInterface
public interface DispatchCompensation {
    void recover(List<AcceptedInsertion> acceptedInsertions,
                 Consumer<GenericStack> recoveredSink);
}
