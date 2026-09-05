package com.moakiee.ae2lt.packaged.logic.multiblock;

import java.util.List;

import org.jetbrains.annotations.Nullable;

public record DispatchPlan(
        List<TargetSlot> targets,
        @Nullable Runnable onCommit,
        @Nullable DispatchCompensation onPartialCommit
) {
    public DispatchPlan(List<TargetSlot> targets, @Nullable Runnable onCommit) {
        this(targets, onCommit, null);
    }
}
