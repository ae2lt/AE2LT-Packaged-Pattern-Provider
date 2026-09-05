package com.moakiee.ae2lt.packaged.logic.multiblock.binding;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.jetbrains.annotations.Nullable;

import appeng.api.crafting.IPatternDetails;

/**
 * Lazy cache of {@link PatternBinding} for each pattern slot.
 *
 * <p>Bindings are computed on the first push that needs them (lazy bind) and
 * invalidated as a unit when any structural premise changes:
 * <ul>
 *   <li>pattern slot edited &mdash; {@link #invalidateAll()} from {@code updatePatterns}</li>
 *   <li>neighbor block changed &mdash; {@link #invalidateAll()} from {@code onNeighborChanged}</li>
 *   <li>wireless connection list changed &mdash; {@link #invalidateAll()} from {@code onHostStateChanged}</li>
 *   <li>recipe table reloaded &mdash; {@link #invalidateAll()} from {@code RecipesUpdatedEvent}</li>
 * </ul>
 *
 * <p>The cache stores {@code PatternBinding} objects (which may be empty
 * &laquo;UNMATCHED&raquo; instances) so negative lookups are also cached and
 * not retried until invalidation.
 *
 * <p>Keyed by pattern identity (the {@code IPatternDetails} returned by
 * {@code PatternDetailsHelper.decodePattern}) rather than equality, because
 * {@code updatePatterns} rebuilds the pattern list wholesale and replaces every
 * instance &mdash; identity matches the cache invalidation lifecycle exactly.
 */
public final class PatternBindingTable {

    private static final long NEGATIVE_BINDING_TTL_TICKS = 40;
    private static final long POSITIVE_BINDING_TTL_TICKS = 200;
    private static final AtomicLong RECIPE_GENERATION = new AtomicLong();

    private long observedRecipeGeneration = RECIPE_GENERATION.get();

    /** Invalidates every provider cache after a server recipe/data reload. */
    public static void invalidateAllForRecipeReload() {
        RECIPE_GENERATION.incrementAndGet();
    }

    // IdentityHashMap is not thread-safe; synchronize all access to keep the
    // get+remove in getFresh atomic and to avoid CME under concurrent grid
    // events. The lock is per-table (instance), so providers don't block each
    // other.
    private final Map<IPatternDetails, PatternBinding> bindings = new IdentityHashMap<>();
    private final Object lock = new Object();

    @Nullable
    public PatternBinding get(IPatternDetails pattern) {
        synchronized (lock) {
            return bindings.get(pattern);
        }
    }

    @Nullable
    public PatternBinding getFresh(IPatternDetails pattern, long gameTick) {
        synchronized (lock) {
            long generation = RECIPE_GENERATION.get();
            if (observedRecipeGeneration != generation) {
                bindings.clear();
                observedRecipeGeneration = generation;
            }
            var binding = bindings.get(pattern);
            if (binding == null) {
                return null;
            }
            long ttl = binding.isMatched()
                    ? POSITIVE_BINDING_TTL_TICKS
                    : NEGATIVE_BINDING_TTL_TICKS;
            if (gameTick - binding.computedAtTick() >= ttl) {
                bindings.remove(pattern);
                return null;
            }
            return binding;
        }
    }

    public void put(IPatternDetails pattern, PatternBinding binding) {
        synchronized (lock) {
            observedRecipeGeneration = RECIPE_GENERATION.get();
            bindings.put(pattern, binding);
        }
    }

    public void invalidate(IPatternDetails pattern) {
        synchronized (lock) {
            bindings.remove(pattern);
        }
    }

    public void invalidateAll() {
        synchronized (lock) {
            bindings.clear();
        }
    }

    public int size() {
        synchronized (lock) {
            return bindings.size();
        }
    }

    /** Snapshot for debug; not part of the hot path. */
    public Map<IPatternDetails, PatternBinding> snapshot() {
        synchronized (lock) {
            return new HashMap<>(bindings);
        }
    }
}
