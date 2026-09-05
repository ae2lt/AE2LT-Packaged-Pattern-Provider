package com.moakiee.ae2lt.packaged.logic.multiblock.binding;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;

/**
 * Batches virtual-craft outputs and flushes them on a fixed interval (default
 * every 10 ticks).
 *
 * <p>Outputs are merged by {@link AEKey} inside the pending buffer so each
 * pattern only contributes one entry per key per flush window, keeping the
 * inserted stack count bounded even when push frequency is very high.
 *
 * <p>The class itself enforces no upper bound on accumulated amount; the
 * outer push path is expected to apply a per-tick admission cap (e.g. 64
 * outputs per lane per tick) before calling {@link #enqueue}.
 */
public final class VirtualBatchAccumulator {

    private static final Logger LOG = LoggerFactory.getLogger(VirtualBatchAccumulator.class);
    private static final String TAG_PENDING = "pending";
    private static final String TAG_LAST_FLUSH_TICK = "last_flush_tick";

    public static final int FLUSH_INTERVAL_TICKS = 10;

    private final Map<AEKey, Long> pending = new LinkedHashMap<>();
    private long lastFlushTick = Long.MIN_VALUE;

    /** Merge {@code outputs} into the pending buffer keyed by {@link AEKey}. */
    public void enqueue(List<GenericStack> outputs) {
        if (outputs == null || outputs.isEmpty()) {
            return;
        }
        for (var stack : outputs) {
            if (stack.amount() <= 0) {
                continue;
            }
            mergePending(stack.what(), stack.amount());
        }
    }

    /**
     * Flush every {@link #FLUSH_INTERVAL_TICKS} ticks. The sink receives a fresh
     * snapshot and returns only the portion which was not delivered. This keeps
     * delivery failures from restoring products that already reached the sink.
     *
     * @return true when a flush actually happened on this call
     */
    public boolean tickFlush(long gameTick, Function<List<GenericStack>, List<GenericStack>> sink) {
        if (lastFlushTick == Long.MIN_VALUE) {
            lastFlushTick = gameTick;
            return false;
        }
        long elapsed;
        try {
            elapsed = Math.subtractExact(gameTick, lastFlushTick);
            if (elapsed < 0) {
                // A restored world can move the clock backwards; do not keep
                // pending outputs asleep until it catches up to an old save.
                elapsed = Long.MAX_VALUE;
            }
        } catch (ArithmeticException overflow) {
            // A wrapped game clock must not suppress flushing forever.
            elapsed = Long.MAX_VALUE;
        }
        if (elapsed < FLUSH_INTERVAL_TICKS) {
            return false;
        }
        lastFlushTick = gameTick;
        if (pending.isEmpty()) {
            return true;
        }
        var merged = snapshotStacks();
        pending.clear();
        try {
            var residual = sink.apply(merged);
            if (residual != null) {
                for (var stack : residual) {
                    if (stack != null && stack.what() != null && stack.amount() > 0) {
                        mergePending(stack.what(), stack.amount());
                    }
                }
            }
        } catch (RuntimeException | LinkageError e) {
            // A sink exception means no delivery result can be trusted. Restore
            // the snapshot; sinks must be exception-safe after committing output.
            for (var stack : merged) {
                mergePending(stack.what(), stack.amount());
            }
            LOG.warn("Virtual batch flush failed; retaining {} stack(s)", merged.size(), e);
        }
        return true;
    }

    /** Force flush regardless of interval; used on host removal / clearContent. */
    public List<GenericStack> drainAll() {
        if (pending.isEmpty()) {
            return List.of();
        }
        var merged = snapshotStacks();
        pending.clear();
        return merged;
    }

    public void writeToNBT(CompoundTag tag) {
        var entries = new ListTag();
        for (var stack : snapshotStacks()) {
            entries.add(GenericStack.writeTag(stack));
        }
        if (!entries.isEmpty()) {
            tag.put(TAG_PENDING, entries);
        }
        if (lastFlushTick != Long.MIN_VALUE) {
            tag.putLong(TAG_LAST_FLUSH_TICK, lastFlushTick);
        }
    }

    public void readFromNBT(CompoundTag tag) {
        clear();
        if (tag.contains(TAG_LAST_FLUSH_TICK, Tag.TAG_LONG)) {
            lastFlushTick = tag.getLong(TAG_LAST_FLUSH_TICK);
        }
        if (!tag.contains(TAG_PENDING, Tag.TAG_LIST)) {
            return;
        }
        var entries = tag.getList(TAG_PENDING, Tag.TAG_COMPOUND);
        for (int i = 0; i < entries.size(); i++) {
            try {
                var stack = GenericStack.readTag(entries.getCompound(i));
                if (stack != null && stack.what() != null && stack.amount() > 0) {
                    mergePending(stack.what(), stack.amount());
                }
            } catch (RuntimeException | LinkageError ignored) {
                LOG.warn("Ignoring malformed virtual batch entry");
            }
        }
    }

    private void mergePending(AEKey key, long amount) {
        if (key == null || amount <= 0) {
            return;
        }
        var previous = pending.get(key);
        if (previous == null) {
            pending.put(key, amount);
            return;
        }
        try {
            pending.put(key, Math.addExact(previous, amount));
        } catch (ArithmeticException overflow) {
            pending.put(key, Long.MAX_VALUE);
            LOG.warn("Virtual batch amount saturated at Long.MAX_VALUE for {}", key);
        }
    }

    private List<GenericStack> snapshotStacks() {
        var merged = new ArrayList<GenericStack>(pending.size());
        for (var entry : pending.entrySet()) {
            merged.add(new GenericStack(entry.getKey(), entry.getValue()));
        }
        return merged;
    }

    public boolean hasPending() {
        return !pending.isEmpty();
    }

    public void clear() {
        pending.clear();
        lastFlushTick = Long.MIN_VALUE;
    }

    /** Snapshot for debug / inspection. Not part of the steady-state hot path. */
    public Map<AEKey, Long> snapshot() {
        return new HashMap<>(pending);
    }
}
