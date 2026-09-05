package com.moakiee.ae2lt.packaged.logic;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;

import appeng.api.config.Actionable;
import appeng.api.config.LockCraftingMode;
import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.IGrid;
import appeng.api.networking.IManagedGridNode;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;

import com.moakiee.ae2lt.packaged.blockentity.PackagedPatternProviderBlockEntity;
import com.moakiee.ae2lt.packaged.item.MultiblockAdapterItem;
import com.moakiee.ae2lt.packaged.logic.multiblock.AdapterPersistentScope;
import com.moakiee.ae2lt.packaged.logic.multiblock.DispatchExecutor;
import com.moakiee.ae2lt.packaged.logic.multiblock.MultiblockAdapter;
import com.moakiee.ae2lt.packaged.logic.multiblock.MultiblockAdapterRegistry;
import com.moakiee.ae2lt.packaged.logic.multiblock.NeighborMainBlockIndex;
import com.moakiee.ae2lt.packaged.logic.multiblock.VirtualCraftingAdapter;
import com.moakiee.ae2lt.packaged.logic.multiblock.binding.BindingMode;
import com.moakiee.ae2lt.packaged.logic.multiblock.binding.LaneCandidate;
import com.moakiee.ae2lt.packaged.logic.multiblock.binding.LaneCooldownTable;
import com.moakiee.ae2lt.packaged.logic.multiblock.binding.LaneKey;
import com.moakiee.ae2lt.packaged.logic.multiblock.binding.LaneRateLimiter;
import com.moakiee.ae2lt.packaged.logic.multiblock.binding.PatternBinding;
import com.moakiee.ae2lt.packaged.logic.multiblock.binding.PatternBindingTable;
import com.moakiee.ae2lt.packaged.logic.multiblock.binding.VirtualBatchAccumulator;
import com.moakiee.ae2lt.packaged.patternprovider.AllowedOutputFilter;
import com.moakiee.ae2lt.packaged.patternprovider.PatternProviderPowerCost;
import com.moakiee.ae2lt.packaged.patternprovider.StablePatternProviderBlockEntity.ProviderMode;
import com.moakiee.ae2lt.packaged.patternprovider.StablePatternProviderBlockEntity.ReturnMode;
import com.moakiee.ae2lt.packaged.patternprovider.StablePatternProviderLogic;

/**
 * Packaged pattern provider scheduling.
 *
 * <p>Compared to the stable provider foundation this class
 * implements:
 * <ul>
 *   <li><b>Bind once on first use.</b> Recipe / structure search runs in
 *       {@link MultiblockAdapter#bind} and caches an opaque handle inside
 *       {@link PatternBindingTable}. Subsequent pushes skip directly to layout.</li>
 *   <li><b>Virtual batch accumulation.</b> Virtual adapters (no in-world delay)
 *       deposit outputs into {@link VirtualBatchAccumulator}; the buffer
 *       flushes every {@value VirtualBatchAccumulator#FLUSH_INTERVAL_TICKS} ticks.
 *       A {@link LaneRateLimiter} caps each lane at 64 pushes per tick.</li>
 *   <li><b>Real dispatch.</b> Per push: optionally return outputs, then run
 *       {@link MultiblockAdapter#canDispatch} &rarr; {@code planWithBinding}.
 *       Failures route the lane through {@link LaneCooldownTable}'s retry
 *       schedule (1, 2, 3, 4, 5, 8, 10, 20, 40, then every 40 ticks).</li>
 *   <li><b>Unified 20-tick auto-return.</b> Real lanes get pulled exactly once
 *       per 20 ticks; virtual lanes are skipped because they never store outputs
 *       in the world.</li>
 * </ul>
 */
public class PackagedPatternProviderLogic extends StablePatternProviderLogic {

    private static final Logger LOG = LoggerFactory.getLogger(PackagedPatternProviderLogic.class);

    /** Maximum virtual-craft acquisitions per lane per game tick. */
    private static final int VIRTUAL_PUSH_CAP_PER_LANE_PER_TICK = 64;

    /** Auto-return polling interval for real-dispatch lanes. */
    private static final int AUTO_RETURN_INTERVAL_TICKS = 20;

    /** Independent cadence for adapter-owned activation retries. */
    private static final int PENDING_RETRY_INTERVAL_TICKS = 20;

    private final NeighborMainBlockIndex neighborIndex;
    private final PatternBindingTable bindingTable = new PatternBindingTable();
    private final LaneCooldownTable cooldownTable = new LaneCooldownTable();
    private final LaneRateLimiter virtualPushLimiter =
            new LaneRateLimiter(VIRTUAL_PUSH_CAP_PER_LANE_PER_TICK);
    private final VirtualBatchAccumulator virtualBatch = new VirtualBatchAccumulator();

    /** Round-robin cursor across real candidates within a single binding. */
    private int realPushRoundRobin;

    /** Last server tick at which {@link #runAutoReturnTick} executed; -MIN forces first run. */
    private long lastAutoReturnTick = Long.MIN_VALUE;

    /** Last server tick at which adapter pending work was polled. */
    private long lastPendingRetryTick = Long.MIN_VALUE;

    /**
     * Sound id captured from the most recent virtual push, replayed by
     * {@link #playFlushSound} when the batch surfaces. Last-write-wins across
     * heterogeneous adapter mixes (rare in practice — providers usually only
     * border one machine kind), and cleared on each flush so an idle window
     * never spuriously replays a stale cue.
     */
    @Nullable
    private ResourceLocation pendingFlushSoundId;

    @Nullable
    private PendingVirtualFlushEffect pendingFlushEffect;

    public PackagedPatternProviderLogic(IManagedGridNode mainNode,
                                        PackagedPatternProviderBlockEntity host) {
        super(mainNode, host);
        this.neighborIndex = new NeighborMainBlockIndex(host.getBlockPos());
    }

    // ===== Pattern lifecycle =====

    @Override
    public void updatePatterns() {
        super.updatePatterns();
        bindingTable.invalidateAll();
    }

    @Override
    public void writeToNBT(net.minecraft.nbt.CompoundTag tag) {
        super.writeToNBT(tag);
        virtualBatch.writeToNBT(tag);
    }

    @Override
    public void readFromNBT(net.minecraft.nbt.CompoundTag tag) {
        super.readFromNBT(tag);
        virtualBatch.readFromNBT(tag);
        pendingFlushSoundId = null;
        pendingFlushEffect = null;
        lastAutoReturnTick = Long.MIN_VALUE;
        lastPendingRetryTick = Long.MIN_VALUE;
    }

    @Override
    public void clearContent() {
        super.clearContent();
        // Pending virtual outputs belong to the provider and must survive an
        // inventory/content reset until they can be delivered or dropped.
        pendingFlushSoundId = null;
        pendingFlushEffect = null;
        if (virtualBatch.hasPending()) {
            alertGridTick();
        }
    }

    public List<GenericStack> drainVirtualBatch() {
        var drained = virtualBatch.drainAll();
        if (!drained.isEmpty()) {
            getProviderHost().saveChanges();
        }
        return drained;
    }

    private void enqueueVirtualOutputs(List<GenericStack> outputs) {
        if (outputs == null || outputs.isEmpty()) {
            return;
        }
        virtualBatch.enqueue(outputs);
        getProviderHost().saveChanges();
    }

    @Override
    public void onNeighborChanged() {
        clearRemovedAdjacentTargetState();
        neighborIndex.invalidate();
        bindingTable.invalidateAll();
        cooldownTable.clear();
        super.onNeighborChanged();
    }

    private void clearRemovedAdjacentTargetState() {
        if (getProviderHost().getProviderMode() != ProviderMode.NORMAL
                || !(getProviderHost().getLevel() instanceof ServerLevel level)) {
            return;
        }
        for (var face : Direction.values()) {
            var pos = getProviderHost().getBlockPos().relative(face);
            if (!level.isLoaded(pos)) {
                continue;
            }
            var be = level.getBlockEntity(pos);
            // Read the old observation without rebuilding the index. A binding
            // (or even an installed pattern) is not required to retire ownership.
            var previous = neighborIndex.getAdapter(face);
            boolean remains;
            if (previous != null) {
                remains = previous.recognizesMain(level, pos, be);
            } else {
                // A cold index has no previous adapter identity. Check all
                // registrations: disabling an adapter does not remove its block.
                remains = MultiblockAdapterRegistry.registrations().stream()
                        .anyMatch(entry -> entry.adapter().recognizesMain(level, pos, be));
            }
            if (!remains) {
                adapterScope().clearFlagsForTarget(pos);
            }
        }
    }

    @Override
    public void onHostStateChanged() {
        bindingTable.invalidateAll();
        pruneStaleLaneState();
        super.onHostStateChanged();
    }

    /**
     * Invoked from the mod-level {@code RecipesUpdatedEvent} listener.
     * Wipes every cached binding so the next push re-runs {@code bind()}.
     */
    public void onRecipesReloaded() {
        bindingTable.invalidateAll();
    }

    /**
     * Hook called by the BlockEntity when the packaged core slot is edited.
     * The set of unlock-able adapter ids may have changed, so every cached
     * binding (which encodes the per-candidate "is this core sufficient?"
     * decision implicitly) needs to be recomputed on next push.
     */
    public void onAdapterSlotChanged() {
        bindingTable.invalidateAll();
        cooldownTable.clear();
    }

    /**
     * Provider-owned persistent flag store handed to multiblock adapters.
     *
     * <p>The host {@link PackagedPatternProviderBlockEntity} implements
     * {@link AdapterPersistentScope} directly; this helper just narrows the
     * cast in one place so the rest of the logic can hand the scope around
     * without each call site repeating the type cast (and so a future host
     * variant that doesn't implement the interface still type-checks here,
     * falling back to {@link AdapterPersistentScope#NOOP}).
     */
    private AdapterPersistentScope adapterScope() {
        if (getProviderHost() instanceof AdapterPersistentScope scope) {
            return scope;
        }
        return AdapterPersistentScope.NOOP;
    }

    // ===== Push path =====

    @Override
    public boolean pushPattern(IPatternDetails patternDetails, KeyCounter[] inputHolder) {
        if (!getGridNode().isActive()) {
            return false;
        }
        if (!getAvailablePatterns().contains(patternDetails)) {
            return false;
        }
        if (getCraftingLockedReason() != LockCraftingMode.NONE) {
            return false;
        }

        var level = getProviderHost().getLevel();
        if (!(level instanceof ServerLevel sl)) {
            return false;
        }

        double cost = PatternProviderPowerCost.totalCost(inputHolder);
        var grid = getGridNode().getGrid();
        if (!PatternProviderPowerCost.canAfford(grid, cost)) {
            return false;
        }

        long gameTick = sl.getGameTime();
        var binding = getOrComputeBinding(sl, patternDetails, gameTick);
        if (!binding.isMatched()) {
            return false;
        }

        if (tryVirtualPush(sl, patternDetails, inputHolder, binding, gameTick)) {
            PatternProviderPowerCost.consumeRaw(grid, cost);
            recordSuccessfulPush(patternDetails);
            return true;
        }

        if (tryRealDispatch(sl, patternDetails, inputHolder, binding, grid, cost, gameTick)) {
            PatternProviderPowerCost.consumeRaw(grid, cost);
            recordSuccessfulPush(patternDetails);
            return true;
        }

        return false;
    }

    /**
     * Tries to admit a single virtual craft into the batch accumulator.
     * One push acquires at most one lane: AE views the push as a single
     * completed craft, regardless of how many candidates exist.
     */
    private boolean tryVirtualPush(ServerLevel level, IPatternDetails pattern, KeyCounter[] inputs,
                                   PatternBinding binding, long gameTick) {
        for (var candidate : binding.candidates()) {
            if (candidate.mode() != BindingMode.VIRTUAL) {
                continue;
            }
            if (!virtualPushLimiter.hasCapacity(candidate.lane(), gameTick)) {
                continue;
            }

            var env = resolveLaneEnv(level, candidate);
            if (env == null) {
                continue;
            }

            if (!(candidate.adapter() instanceof VirtualCraftingAdapter virtualAdapter)) {
                continue;
            }

            var result = virtualAdapter.planVirtualWithBinding(
                    env.level(), env.pos(), pattern, inputs, candidate.handle(), getActionSource());
            if (result == null || result.outputs().isEmpty()) {
                continue;
            }

            if (!virtualPushLimiter.tryAcquire(candidate.lane(), gameTick)) {
                continue;
            }
            enqueueVirtualOutputs(result.outputs());
            // Remember the adapter's flush cue for the next batch surface;
            // last-write-wins is fine because providers typically border one
            // machine kind and players hear at most one cue per 10t window.
            var sid = virtualAdapter.flushSoundId();
            if (sid != null) {
                pendingFlushSoundId = sid;
            }
            pendingFlushEffect = new PendingVirtualFlushEffect(
                    virtualAdapter, env.level(), env.pos(), candidate.handle());
            return true;
        }
        return false;
    }

    /**
     * Tries to dispatch one real candidate using the three-step flow:
     * extract first, gate on {@code canDispatch}, then plan + execute.
     */
    private boolean tryRealDispatch(ServerLevel level, IPatternDetails pattern, KeyCounter[] inputs,
                                    PatternBinding binding, IGrid grid,
                                    double inputCost, long gameTick) {
        var realCandidates = new ArrayList<LaneCandidate>();
        for (var c : binding.candidates()) {
            if (c.mode() == BindingMode.REAL) {
                realCandidates.add(c);
            }
        }
        if (realCandidates.isEmpty()) {
            return false;
        }

        int total = realCandidates.size();
        var filter = getOrBuildOutputFilter();
        for (int i = 0; i < total; i++) {
            int idx = Math.floorMod(realPushRoundRobin + i, total);
            var candidate = realCandidates.get(idx);

            if (!cooldownTable.isReady(candidate.lane(), gameTick)) {
                continue;
            }

            var env = resolveLaneEnv(level, candidate);
            if (env == null) {
                cooldownTable.recordFailure(candidate.lane(), gameTick);
                continue;
            }

            // OFF means the provider must not pull completed products from the
            // machine. Previously this pre-dispatch cleanup ran unconditionally,
            // which made auto-return effectively active even while its GUI button
            // showed OFF.
            if (getProviderHost().getReturnMode() == ReturnMode.AUTO) {
                var extracted = candidate.adapter().extractOutputs(
                        env.level(), env.pos(), filter, getActionSource(), adapterScope());
                if (!extracted.isEmpty()) {
                    enqueueVirtualOutputs(insertOutputsToReturnInv(extracted));
                }
            }

            // 1. Cheap gate: is the machine accepting?
            if (!candidate.adapter().canDispatch(
                    env.level(), env.pos(), candidate.handle(), adapterScope())) {
                cooldownTable.recordFailure(candidate.lane(), gameTick);
                continue;
            }

            // 2. Build and execute the plan
            var plan = candidate.adapter().planWithBinding(
                    env.level(), env.pos(), pattern, inputs, candidate.handle(),
                    getActionSource(), adapterScope());
            // AUTO extraction above may have charged output power. Recheck before
            // DispatchExecutor can commit inputs so consumeRaw never undercharges.
            if (plan != null && !PatternProviderPowerCost.canAfford(grid, inputCost)) {
                return false;
            }
            if (plan == null
                    || !DispatchExecutor.execute(
                                    plan,
                                    getActionSource(),
                                    getInternalReturnInv(),
                                    residual -> {
                                        enqueueVirtualOutputs(List.of(residual));
                                        alertGridTick();
                                        return true;
                                    })
                            .success()) {
                cooldownTable.recordFailure(candidate.lane(), gameTick);
                continue;
            }

            cooldownTable.recordSuccess(candidate.lane());
            realPushRoundRobin = (idx + 1) % total;
            return true;
        }
        return false;
    }

    // ===== Tick =====

    /**
     * Tell the grid ticker we still have work whenever any virtual product is
     * waiting in the batch. Otherwise the ticker can put us to SLEEP between
     * pushes and the trailing partial batch (the last craft of an order, e.g.
     * the final 4 of a 12-product order on a 9x9 EC table) would never flush
     * &mdash; users see "短 4 个" no matter how large the order is.
     */
    @Override
    protected boolean hasAutoReturnWork() {
        return getProviderHost().getReturnMode() == ReturnMode.AUTO;
    }

    @Override
    public boolean hasAnyTickWork() {
        return super.hasAnyTickWork() || virtualBatch.hasPending();
    }

    public void tickPendingAdapters(ServerLevel providerLevel) {
        long gameTick = providerLevel.getGameTime();
        if (lastPendingRetryTick != Long.MIN_VALUE
                && elapsedTicks(gameTick, lastPendingRetryTick) < PENDING_RETRY_INTERVAL_TICKS) {
            return;
        }
        lastPendingRetryTick = gameTick;

        if (getProviderHost().getProviderMode() == ProviderMode.NORMAL) {
            for (var face : neighborIndex.adapterFaces(providerLevel)) {
                var adapter = neighborIndex.getAdapter(face);
                if (adapter == null || adapter instanceof VirtualCraftingAdapter) {
                    continue;
                }
                tickPendingAdapter(
                        adapter, providerLevel, getProviderHost().getBlockPos().relative(face));
            }
            return;
        }

        var server = providerLevel.getServer();
        for (var connection : getValidConnections(providerLevel, gameTick)) {
            var targetLevel = server.getLevel(connection.dimension());
            if (targetLevel == null || !targetLevel.isLoaded(connection.pos())) {
                continue;
            }
            var be = targetLevel.getBlockEntity(connection.pos());
            var adapter = MultiblockAdapterRegistry.find(
                    targetLevel, connection.pos(), be);
            if (adapter == null || adapter instanceof VirtualCraftingAdapter) {
                continue;
            }
            tickPendingAdapter(adapter, targetLevel, connection.pos());
        }
    }

    private void tickPendingAdapter(MultiblockAdapter adapter,
                                    ServerLevel level,
                                    BlockPos pos) {
        try {
            adapter.tickPending(level, pos, adapterScope());
        } catch (RuntimeException | LinkageError e) {
            LOG.warn("Adapter pending tick failed at {} in {}",
                    pos, level.dimension().location(), e);
        }
    }

    @Override
    public void tickAutoReturn() {
        if (!hasAnyTickWork()) {
            return;
        }

        var level = getProviderHost().getLevel();
        if (!(level instanceof ServerLevel sl)) {
            return;
        }
        long gameTick = sl.getGameTime();

        // Virtual batch always ticks; outputs surface every 10 ticks.
        // Persist the transition after a real flush so clearing a delivered
        // batch or retaining a residual cannot be lost across a crash.
        boolean hadPendingVirtualOutputs = virtualBatch.hasPending();
        virtualBatch.tickFlush(gameTick, stacks -> {
            var residual = insertOutputsToReturnInv(stacks);
            if (residual.isEmpty()) {
                runPendingFlushEffectSafely();
                playFlushSoundSafely(sl);
            }
            return residual;
        });
        if (hadPendingVirtualOutputs) {
            getProviderHost().saveChanges();
        }

        if (getProviderHost().getReturnMode() != ReturnMode.AUTO || !getGridNode().isActive()) {
            return;
        }

        if (lastAutoReturnTick == Long.MIN_VALUE
                || elapsedTicks(gameTick, lastAutoReturnTick) >= AUTO_RETURN_INTERVAL_TICKS) {
            runAutoReturnTick(sl, gameTick);
            lastAutoReturnTick = gameTick;
        }
    }

    private static long elapsedTicks(long current, long previous) {
        try {
            long elapsed = Math.subtractExact(current, previous);
            return elapsed < 0 ? Long.MAX_VALUE : elapsed;
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    private void runAutoReturnTick(ServerLevel level, long gameTick) {
        var filter = getOrBuildOutputFilter();
        if (getProviderHost().getProviderMode() == ProviderMode.NORMAL) {
            autoReturnNormal(level, filter);
        } else {
            autoReturnWireless(level, filter, gameTick);
        }
    }

    private void autoReturnNormal(ServerLevel level, AllowedOutputFilter filter) {
        for (var face : neighborIndex.adapterFaces(level)) {
            var adapter = neighborIndex.getAdapter(face);
            if (adapter == null || adapter instanceof VirtualCraftingAdapter) {
                continue;
            }
            if (filter.isEmpty() && !adapter.supportsPatternIndependentHarvest()) {
                continue;
            }
            var pos = getProviderHost().getBlockPos().relative(face);
            var outputs = adapter.extractOutputs(level, pos, filter, getActionSource(), adapterScope());
            if (!outputs.isEmpty()) {
                enqueueVirtualOutputs(insertOutputsToReturnInv(outputs));
            }
        }
    }

    private void autoReturnWireless(ServerLevel providerLevel,
                                     AllowedOutputFilter filter,
                                     long gameTick) {
        var valid = getValidConnections(providerLevel, gameTick);
        if (valid.isEmpty()) {
            return;
        }
        var server = providerLevel.getServer();
        for (var conn : valid) {
            var targetLevel = server.getLevel(conn.dimension());
            if (targetLevel == null || !targetLevel.isLoaded(conn.pos())) {
                continue;
            }
            // BE may be null for block-only targets; pass through to find
            // regardless so each adapter can decide whether it applies.
            var be = targetLevel.getBlockEntity(conn.pos());
            var adapter = MultiblockAdapterRegistry.find(targetLevel, conn.pos(), be);
            if (adapter == null || adapter instanceof VirtualCraftingAdapter) {
                continue;
            }
            if (filter.isEmpty() && !adapter.supportsPatternIndependentHarvest()) {
                continue;
            }
            var outputs = adapter.extractOutputs(targetLevel, conn.pos(), filter, getActionSource(), adapterScope());
            if (!outputs.isEmpty()) {
                enqueueVirtualOutputs(insertOutputsToReturnInv(outputs));
            }
        }
    }

    // ===== Binding =====

    private PatternBinding getOrComputeBinding(ServerLevel level, IPatternDetails pattern, long gameTick) {
        var existing = bindingTable.getFresh(pattern, gameTick);
        if (existing != null) {
            return existing;
        }
        var fresh = computeBinding(level, pattern, gameTick);
        bindingTable.put(pattern, fresh);
        return fresh;
    }

    private PatternBinding computeBinding(ServerLevel level, IPatternDetails pattern, long gameTick) {
        var candidates = new ArrayList<LaneCandidate>();
        var mode = getProviderHost().getProviderMode();
        var installedCard = installedAdapterStack();

        if (mode == ProviderMode.NORMAL) {
            for (var face : neighborIndex.adapterFaces(level)) {
                var adapter = neighborIndex.getAdapter(face);
                if (adapter == null) {
                    continue;
                }
                var pos = getProviderHost().getBlockPos().relative(face);
                if (!isAdapterUnlocked(adapter, level, pos, installedCard)) {
                    continue;
                }
                var result = adapter.bind(level, pos, pattern);
                if (result != null) {
                    candidates.add(new LaneCandidate(
                            new LaneKey.FaceLane(face), adapter, result.handle(), result.mode()));
                }
            }
        } else {
            var server = level.getServer();
            for (var conn : getValidConnections(level, gameTick)) {
                var targetLevel = server.getLevel(conn.dimension());
                if (targetLevel == null || !targetLevel.isLoaded(conn.pos())) {
                    continue;
                }
                // BE may be null for non-tile blocks; each adapter's
                // recognizesMain must filter for itself.
                var be = targetLevel.getBlockEntity(conn.pos());
                var adapter = MultiblockAdapterRegistry.find(targetLevel, conn.pos(), be);
                if (adapter == null) {
                    continue;
                }
                if (!isAdapterUnlocked(adapter, targetLevel, conn.pos(), installedCard)) {
                    continue;
                }
                var result = adapter.bind(targetLevel, conn.pos(), pattern);
                if (result != null) {
                    candidates.add(new LaneCandidate(
                            new LaneKey.ConnLane(conn), adapter, result.handle(), result.mode()));
                }
            }
        }

        return candidates.isEmpty()
                ? PatternBinding.unmatched(gameTick)
                : new PatternBinding(candidates, gameTick);
    }

    /**
     * Resolves a {@link LaneCandidate} to its live world position and verifies the
     * adapter still recognizes the block there. Returns {@code null} when the
     * binding is stale (block changed / chunk unloaded / wireless target gone).
     */
    @Nullable
    private LaneEnv resolveLaneEnv(ServerLevel providerLevel, LaneCandidate candidate) {
        var lane = candidate.lane();
        if (lane instanceof LaneKey.FaceLane faceLane) {
            var pos = getProviderHost().getBlockPos().relative(faceLane.face());
            if (!providerLevel.isLoaded(pos)) {
                // The adjacent chunk may be temporarily unloaded. Preserve any
                // pending adapter ownership until the target is confirmed gone.
                return null;
            }
            // BE may be null for plain blocks. Defer the "is this still my
            // multiblock?" check to recognizesMain, which each adapter
            // implements with the right BE / BlockState mix.
            var be = providerLevel.getBlockEntity(pos);
            if (!candidate.adapter().recognizesMain(providerLevel, pos, be)) {
                adapterScope().clearFlagsForTarget(pos);
                return null;
            }
            return new LaneEnv(providerLevel, pos);
        }
        if (lane instanceof LaneKey.ConnLane connLane) {
            var conn = connLane.connection();
            var targetLevel = providerLevel.getServer().getLevel(conn.dimension());
            if (targetLevel == null || !targetLevel.isLoaded(conn.pos())) {
                // An unloaded target may only be temporarily absent; preserve
                // pending ownership until the connection validator removes it.
                return null;
            }
            var be = targetLevel.getBlockEntity(conn.pos());
            if (!candidate.adapter().recognizesMain(targetLevel, conn.pos(), be)) {
                // The chunk is loaded, so this is a confirmed replacement/removal
                // rather than a transient unload. Drop all provider-owned state.
                adapterScope().clearFlagsForTarget(conn.pos());
                return null;
            }
            return new LaneEnv(targetLevel, conn.pos());
        }
        return null;
    }

    private void pruneStaleLaneState() {
        Set<LaneKey> active = new HashSet<>();
        for (var dir : EnumSet.allOf(Direction.class)) {
            active.add(new LaneKey.FaceLane(dir));
        }
        for (var conn : getProviderHost().getConnections()) {
            active.add(new LaneKey.ConnLane(conn));
        }
        cooldownTable.retainAll(active);
        virtualPushLimiter.retainAll(active);
    }

    // ===== Output delivery =====

    /**
     * Inserts packaged-provider outputs without silently swallowing products.
     *
     * <p>AE2LT's machine auto-return now uses transactional output sinks, but
     * virtual crafts produce their outputs directly inside this addon's batch
     * accumulator. If the grid cannot currently pay for or accept those outputs,
     * they must remain queued here because there is no remote machine to re-poll.
     *
     * <p>This method delivers whatever the grid can pay for right now, then
     * pushes any leftover back into {@link #virtualBatch} so the next flush (or
     * the next time energy is available) can finish delivering it.
     */
    private List<GenericStack> insertOutputsToReturnInv(List<GenericStack> outputs) {
        if (outputs.isEmpty()) {
            return List.of();
        }
        var grid = getGridNode().getGrid();
        var returnInv = getInternalReturnInv();
        var retained = new ArrayList<GenericStack>();

        for (var stack : outputs) {
            if (stack == null || stack.what() == null || stack.amount() <= 0) {
                continue;
            }
            long total = stack.amount();
            long actuallyInserted = 0;
            long affordable;
            try {
                affordable = PatternProviderPowerCost.maxAffordable(grid, stack.what(), total);
            } catch (RuntimeException | LinkageError e) {
                LOG.warn("Virtual output affordability check failed for {} x{}", stack.what(), total, e);
                affordable = 0;
            }
            if (affordable > 0) {
                long inserted;
                try {
                    inserted = Math.max(0L, Math.min(affordable,
                            returnInv.insert(0, stack.what(), affordable, Actionable.MODULATE)));
                } catch (RuntimeException | LinkageError e) {
                    LOG.warn("Virtual output insertion failed for {} x{}", stack.what(), affordable, e);
                    inserted = 0;
                }
                if (inserted > 0) {
                    // The item is already in AE2's return inventory. If charging
                    // unexpectedly fails, retain the delivered amount rather than
                    // requeueing it and duplicating the product.
                    try {
                        PatternProviderPowerCost.consume(grid, stack.what(), inserted);
                    } catch (RuntimeException | LinkageError e) {
                        LOG.warn("Virtual output energy charge failed after delivery for {} x{}",
                                stack.what(), inserted, e);
                    }
                    actuallyInserted = inserted;
                }
            }
            long undelivered = total - actuallyInserted;
            if (undelivered > 0) {
                addMergedResidual(retained, stack.what(), undelivered);
            }
        }

        if (!retained.isEmpty()) {
            try {
                alertGridTick();
            } catch (RuntimeException | LinkageError e) {
                // Delivery accounting is already complete. Never let a ticker
                // notification failure restore products that reached returnInv.
                LOG.warn("Could not wake provider for retained virtual outputs", e);
            }
        }
        return List.copyOf(retained);
    }

    private static void addMergedResidual(List<GenericStack> residuals,
                                          appeng.api.stacks.AEKey key,
                                          long amount) {
        if (key == null || amount <= 0) {
            return;
        }
        for (int i = 0; i < residuals.size(); i++) {
            var existing = residuals.get(i);
            if (existing.what().equals(key)) {
                long merged;
                try {
                    merged = Math.addExact(existing.amount(), amount);
                } catch (ArithmeticException overflow) {
                    merged = Long.MAX_VALUE;
                }
                residuals.set(i, new GenericStack(key, merged));
                return;
            }
        }
        residuals.add(new GenericStack(key, amount));
    }

    /**
     * Optional "products arrived" cue, played at the provider after a virtual
     * batch surfaces. Only fires when the contributing adapter explicitly
     * supplies a signature sound id via
     * {@link VirtualCraftingAdapter#flushSoundId()}; adapters that don't
     * (e.g. plain crafting tables) stay silent — the player already hears
     * their AE terminal click on receipt, so a generic chime would be noise.
     *
     * <p>Volume / pitch are tuned low so cluster setups (many providers
     * flushing in the same tick) don't roar.
     */
    private void playFlushSoundSafely(ServerLevel level) {
        try {
            var sound = resolveFlushSoundEvent();
            // Consume the cached id either way so a stale id from one batch can't
            // bleed into the next; the next push will set a fresh one.
            pendingFlushSoundId = null;
            if (sound == null) {
                return;
            }
            var pos = getProviderHost().getBlockPos();
            level.playSound(null, pos, sound, SoundSource.BLOCKS, 0.5f, 1.0f);
        } catch (RuntimeException | LinkageError e) {
            pendingFlushSoundId = null;
            LOG.warn("Virtual batch flush sound failed", e);
        }
    }

    /**
     * Looks up the most recently cached sound id in the live registry.
     * Returns {@code null} when there's no cached id, or when the id resolves
     * to nothing (mod absent / sound renamed) — callers fall back to the
     * generic cue in that case.
     */
    @Nullable
    private SoundEvent resolveFlushSoundEvent() {
        var id = pendingFlushSoundId;
        if (id == null) {
            return null;
        }
        return BuiltInRegistries.SOUND_EVENT.get(id);
    }

    private void runPendingFlushEffectSafely() {
        var effect = pendingFlushEffect;
        pendingFlushEffect = null;
        if (effect == null) {
            return;
        }
        try {
            effect.adapter().onVirtualBatchFlush(
                    effect.level(), effect.pos(), effect.handle(), getActionSource());
        } catch (RuntimeException | LinkageError e) {
            LOG.warn("Virtual batch adapter flush effect failed at {} in {}",
                    effect.pos(), effect.level().dimension().location(), e);
        }
    }

    // ===== Packaged core gating =====

    /**
     * @return the packaged-core ItemStack currently installed in the provider's
     * adapter slot, or {@link ItemStack#EMPTY} when the slot is empty.
     */
    private ItemStack installedAdapterStack() {
        if (getProviderHost() instanceof PackagedPatternProviderBlockEntity packaged) {
            return packaged.getInstalledAdapterStack();
        }
        return ItemStack.EMPTY;
    }

    /**
     * Decides whether the adapter at {@code pos} is allowed to participate in
     * binding given the currently-installed packaged core. Adapters whose
     * {@code requiredAdapterId} returns null are always allowed; otherwise the
     * installed core must {@link MultiblockAdapterItem#covers} the required id
     * (this handles EC's tier-cover-down chain transparently).
     */
    private static boolean isAdapterUnlocked(MultiblockAdapter adapter,
                                              ServerLevel level,
                                              BlockPos pos,
                                              ItemStack installedCard) {
        var required = adapter.requiredAdapterId(level, pos);
        if (required == null) {
            return true;
        }
        return MultiblockAdapterItem.stackCovers(installedCard, required);
    }

    // ===== Helpers =====

    private record LaneEnv(ServerLevel level, BlockPos pos) {}

    private record PendingVirtualFlushEffect(VirtualCraftingAdapter adapter,
                                             ServerLevel level,
                                             BlockPos pos,
                                             Object handle) {
    }

}
