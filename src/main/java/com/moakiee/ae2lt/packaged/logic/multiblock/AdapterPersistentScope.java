package com.moakiee.ae2lt.packaged.logic.multiblock;

import net.minecraft.core.BlockPos;

import com.moakiee.ae2lt.packaged.patternprovider.StablePatternProviderBlockEntity.ReturnMode;

/**
 * Per-provider persistent flag store handed to {@link MultiblockAdapter}s.
 *
 * <p>An adapter that needs cross-tick / cross-restart state about a specific
 * target position (typical example: "this multiblock currently holds a craft
 * AE has paid for") can stash it here without reaching into the target
 * machine's NBT, which is brittle &mdash; many third-party block entities
 * (e.g. Botania's {@code SimpleInventoryBlockEntity}) deliberately skip
 * {@code super.saveAdditional}, which silently drops NeoForge's
 * {@code customPersistentData} and {@code attachments} on disk save.
 *
 * <p>Flags are addressed by {@code (targetPos, key)}. The {@code key} is a
 * free-form string the adapter chooses; recommend namespacing with the
 * adapter id (e.g. {@code "botania_runic_altar:reagent_dispatched"}) so
 * concurrent adapters on the same provider cannot collide. The owning
 * {@code PackagedPatternProviderBlockEntity} serializes its full flag map in
 * {@code saveAdditional}, so values survive chunk unload and server restart.
 *
 * <p>{@link #NOOP} is provided for callers that legitimately don't want
 * persistence (e.g. unit tests, or the fallback default-method bridges in
 * {@code MultiblockAdapter}).
 */
public interface AdapterPersistentScope {

    AdapterPersistentScope NOOP = new AdapterPersistentScope() {
        @Override
        public void setFlag(BlockPos targetPos, String key) {
        }

        @Override
        public boolean hasFlag(BlockPos targetPos, String key) {
            return false;
        }

        @Override
        public void clearFlag(BlockPos targetPos, String key) {
        }
    };

    /**
     * Current output-return policy. Legacy callers retain automatic harvesting;
     * provider hosts override this with their existing return-mode accessor.
     */
    default ReturnMode getReturnMode() {
        return ReturnMode.AUTO;
    }

    /** Marks {@code (targetPos, key)} as true. Idempotent. */
    void setFlag(BlockPos targetPos, String key);

    /** Returns true iff {@link #setFlag} was called for the same arguments and {@link #clearFlag} has not. */
    boolean hasFlag(BlockPos targetPos, String key);

    /** Marks {@code (targetPos, key)} as false. Idempotent. */
    void clearFlag(BlockPos targetPos, String key);

    /** Stores a small adapter-owned value. Implementations may persist it across restarts. */
    default void setState(BlockPos targetPos, String key, String value) {
    }

    /** Returns the stored adapter-owned value, or {@code null} when none exists. */
    default String getState(BlockPos targetPos, String key) {
        return null;
    }

    /** Removes one adapter-owned value. Idempotent. */
    default void clearState(BlockPos targetPos, String key) {
    }

    /** Removes all adapter flags and values associated with a target position. */
    default void clearFlagsForTarget(BlockPos targetPos) {
    }
}
