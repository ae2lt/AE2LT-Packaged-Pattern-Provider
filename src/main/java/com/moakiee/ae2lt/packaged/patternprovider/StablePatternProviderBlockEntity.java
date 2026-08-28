package com.moakiee.ae2lt.packaged.patternprovider;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

import appeng.api.implementations.blockentities.PatternContainerGroup;
import appeng.api.stacks.AEItemKey;
import appeng.api.util.AECableType;
import appeng.blockentity.crafting.PatternProviderBlockEntity;

import com.moakiee.ae2lt.api.patternprovider.WirelessPatternProviderHost;
import com.moakiee.ae2lt.api.patternprovider.WirelessPatternProviderPolicy;
import com.moakiee.ae2lt.logic.wireless.support.WirelessConnectionLists;
import com.moakiee.ae2lt.logic.wireless.support.WirelessConnectionRef;
import com.moakiee.ae2lt.logic.wireless.support.WirelessConnectionValidator;

/**
 * PP-owned block-entity foundation for the packaged provider.
 *
 * <p>NBT and stream field names intentionally match AE2LT 1.1/main so existing
 * packaged-provider worlds load without data conversion.
 */
public abstract class StablePatternProviderBlockEntity
        extends PatternProviderBlockEntity
        implements WirelessPatternProviderHost {
    public static final int SLOTS_PER_PAGE = 36;
    public static final int MAX_WIRELESS_CONNECTIONS = 1024;

    private static final int PERIODIC_PRUNE_INTERVAL_TICKS = 100;
    private static final int PERIODIC_PRUNE_MAX_CHECKS = 64;

    private static final double IDLE_BASE = 5.0;
    private static final double IDLE_WIRELESS_BONUS = 5.0;
    private static final double IDLE_PER_CONNECTION = 1.0;
    private static final double IDLE_FAST_MULTIPLIER = 1.5;

    private static final String TAG_PROVIDER_MODE = "OverloadMode";
    private static final String TAG_LEGACY_AUTO_RETURN = "AutoReturn";
    private static final String TAG_RETURN_MODE = "ReturnMode";
    private static final String TAG_WIRELESS_DISPATCH_MODE = "WirelessDispatchMode";
    private static final String TAG_WIRELESS_SPEED_MODE = "WirelessSpeedMode";
    private static final String TAG_FILTERED_IMPORT = "FilteredImport";
    private static final String TAG_CONNECTIONS = "WirelessConnections";

    public enum ProviderMode {
        NORMAL,
        WIRELESS
    }

    public enum ReturnMode {
        OFF,
        AUTO,
        EJECT
    }

    public enum WirelessDispatchMode {
        SINGLE_TARGET,
        EVEN_DISTRIBUTION
    }

    public enum WirelessSpeedMode {
        NORMAL,
        FAST
    }

    public record WirelessConnection(
            ResourceKey<Level> dimension,
            BlockPos pos,
            Direction boundFace)
            implements WirelessConnectionRef {
        private static final String TAG_DIMENSION = "Dim";
        private static final String TAG_POSITION = "Pos";
        private static final String TAG_FACE = "Face";

        @Override
        public CompoundTag toTag() {
            var tag = new CompoundTag();
            tag.putString(TAG_DIMENSION, dimension.location().toString());
            tag.putLong(TAG_POSITION, pos.asLong());
            tag.putInt(TAG_FACE, boundFace.get3DDataValue());
            return tag;
        }

        public static WirelessConnection fromTag(CompoundTag tag) {
            if (!tag.contains(TAG_DIMENSION, Tag.TAG_STRING)
                    || !tag.contains(TAG_POSITION, Tag.TAG_LONG)
                    || !tag.contains(TAG_FACE, Tag.TAG_INT)) {
                return null;
            }
            try {
                var dimension = ResourceKey.create(
                        Registries.DIMENSION,
                        new ResourceLocation(tag.getString(TAG_DIMENSION)));
                return new WirelessConnection(
                        dimension,
                        BlockPos.of(tag.getLong(TAG_POSITION)),
                        Direction.from3DDataValue(tag.getInt(TAG_FACE)));
            } catch (RuntimeException ignored) {
                return null;
            }
        }
    }

    /**
     * Decoded form of the AE2LT 1.x provider state. Kept package-visible so
     * migration fixtures can verify the frozen on-disk contract without
     * constructing a live AE2 block entity.
     */
    record PersistentState(
            ProviderMode providerMode,
            ReturnMode returnMode,
            WirelessDispatchMode wirelessDispatchMode,
            WirelessSpeedMode wirelessSpeedMode,
            boolean filteredImport,
            List<WirelessConnection> connections) {
    }

    private ProviderMode providerMode = ProviderMode.NORMAL;
    private ReturnMode returnMode = ReturnMode.OFF;
    private WirelessDispatchMode wirelessDispatchMode =
            WirelessDispatchMode.EVEN_DISTRIBUTION;
    private WirelessSpeedMode wirelessSpeedMode = WirelessSpeedMode.NORMAL;
    private boolean filteredImport;

    private final List<WirelessConnection> connections = new ArrayList<>();
    private int invalidConnectionScanCursor;

    protected StablePatternProviderBlockEntity(
            BlockEntityType<?> type, BlockPos pos, BlockState blockState) {
        super(type, pos, blockState);
    }

    public static void serverTick(
            Level level,
            BlockPos pos,
            BlockState state,
            StablePatternProviderBlockEntity blockEntity) {
        // Skip work after setRemoved: the AE2 block-entity ticker can keep invoking
        // us for one extra tick after removal, and any reference to the (now-detached)
        // grid node / adapter state can throw or recurse.
        if (blockEntity.isRemoved()) {
            return;
        }
        if (level instanceof ServerLevel serverLevel) {
            blockEntity.tickWirelessConnectionCleanup(serverLevel);
            blockEntity.serverTickAdditional(serverLevel);
        }
    }

    /** Host-specific server work, such as AE2LT frequency binding. */
    protected void serverTickAdditional(ServerLevel level) {
    }

    @Nullable
    protected final StablePatternProviderLogic getStableLogic() {
        return getLogic() instanceof StablePatternProviderLogic stable ? stable : null;
    }

    @Override
    public void onReady() {
        super.onReady();
        recomputeIdlePower();
    }

    protected final void recomputeIdlePower() {
        double idle = IDLE_BASE;
        if (providerMode == ProviderMode.WIRELESS) {
            idle += IDLE_WIRELESS_BONUS;
            idle += connections.size() * IDLE_PER_CONNECTION;
            if (wirelessSpeedMode == WirelessSpeedMode.FAST) {
                idle *= IDLE_FAST_MULTIPLIER;
            }
        }
        getMainNode().setIdlePowerUsage(idle);
    }

    protected final void notifyLogicStateChanged() {
        var stable = getStableLogic();
        if (stable != null) {
            stable.onHostStateChanged();
        }
    }

    public void onNeighborChanged() {
        var stable = getStableLogic();
        if (stable != null) {
            stable.onNeighborChanged();
        }
    }

    @Override
    public void saveChanges() {
        super.saveChanges();
        if (level != null && !level.isClientSide()) {
            var stable = getStableLogic();
            if (stable != null) {
                stable.onPersistentStateChanged();
            }
        }
    }

    @Override
    public EnumSet<Direction> getTargets() {
        return providerMode == ProviderMode.WIRELESS
                ? EnumSet.noneOf(Direction.class)
                : super.getTargets();
    }

    @Override
    public AECableType getCableConnectionType(Direction direction) {
        return AECableType.SMART;
    }

    public ProviderMode getProviderMode() {
        return providerMode;
    }

    public void setProviderMode(ProviderMode mode) {
        if (providerMode == mode) {
            return;
        }
        providerMode = mode;
        recomputeIdlePower();
        notifyLogicStateChanged();
        saveChanges();
        markForUpdate();
    }

    public ReturnMode getReturnMode() {
        return returnMode;
    }

    public void setReturnMode(ReturnMode mode) {
        if (returnMode == mode) {
            return;
        }
        returnMode = mode;
        notifyLogicStateChanged();
        saveChanges();
        markForUpdate();
    }

    public WirelessDispatchMode getWirelessDispatchMode() {
        return wirelessDispatchMode;
    }

    public void setWirelessDispatchMode(WirelessDispatchMode mode) {
        if (wirelessDispatchMode == mode) {
            return;
        }
        wirelessDispatchMode = mode;
        notifyLogicStateChanged();
        saveChanges();
        markForUpdate();
    }

    public WirelessSpeedMode getWirelessSpeedMode() {
        return wirelessSpeedMode;
    }

    public void setWirelessSpeedMode(WirelessSpeedMode mode) {
        if (wirelessSpeedMode == mode) {
            return;
        }
        wirelessSpeedMode = mode;
        recomputeIdlePower();
        notifyLogicStateChanged();
        saveChanges();
        markForUpdate();
    }

    public boolean isFilteredImport() {
        return filteredImport;
    }

    public void setFilteredImport(boolean filtered) {
        if (filteredImport == filtered) {
            return;
        }
        filteredImport = filtered;
        saveChanges();
        markForUpdate();
    }

    @Override
    public boolean addOrUpdateConnection(
            ResourceKey<Level> dimension, BlockPos pos, Direction boundFace) {
        if (level != null && !level.dimension().equals(dimension)) {
            return false;
        }
        int index = WirelessConnectionLists.indexOf(connections, dimension, pos);
        var updated = new WirelessConnection(dimension, pos.immutable(), boundFace);
        if (index >= 0) {
            if (connections.get(index).equals(updated)) {
                return true;
            }
            connections.set(index, updated);
            onConnectionsChanged(false);
            return true;
        }
        if (connections.size() >= MAX_WIRELESS_CONNECTIONS) {
            return false;
        }
        connections.add(updated);
        onConnectionsChanged(true);
        return true;
    }

    @Override
    public boolean removeConnection(ResourceKey<Level> dimension, BlockPos pos) {
        int index = WirelessConnectionLists.indexOf(connections, dimension, pos);
        if (index < 0) {
            return false;
        }
        connections.remove(index);
        onConnectionsChanged(true);
        return true;
    }

    @Override
    public List<WirelessConnection> getConnections() {
        return Collections.unmodifiableList(connections);
    }

    private void onConnectionsChanged(boolean capacityChanged) {
        invalidConnectionScanCursor = 0;
        if (capacityChanged) {
            recomputeIdlePower();
        }
        notifyLogicStateChanged();
        saveChanges();
        markForUpdate();
    }

    public int clearInvalidConnections() {
        return pruneInvalidConnections(Integer.MAX_VALUE);
    }

    public int pruneInvalidConnections(int maxChecks) {
        if (!(level instanceof ServerLevel serverLevel)
                || maxChecks <= 0
                || connections.isEmpty()) {
            return 0;
        }
        int maxDistance = WirelessPatternProviderPolicy.maxDistance();
        var result = WirelessConnectionLists.prune(
                connections,
                invalidConnectionScanCursor,
                maxChecks,
                connection -> WirelessConnectionValidator.validate(
                        serverLevel,
                        worldPosition,
                        connection,
                        maxDistance) == WirelessConnectionValidator.Status.REMOVE);
        invalidConnectionScanCursor = result.nextCursor();
        if (result.removed() > 0) {
            recomputeIdlePower();
            notifyLogicStateChanged();
            saveChanges();
            markForUpdate();
        }
        return result.removed();
    }

    private void tickWirelessConnectionCleanup(ServerLevel serverLevel) {
        if (!connections.isEmpty()
                && WirelessConnectionValidator.shouldRunPeriodicPrune(
                        serverLevel,
                        worldPosition,
                        PERIODIC_PRUNE_INTERVAL_TICKS)) {
            pruneInvalidConnections(PERIODIC_PRUNE_MAX_CHECKS);
        }
    }

    @Override
    protected void writeToStream(FriendlyByteBuf data) {
        super.writeToStream(data);
        data.writeByte(providerMode.ordinal());
        data.writeByte(returnMode.ordinal());
        data.writeByte(wirelessDispatchMode.ordinal());
        data.writeByte(wirelessSpeedMode.ordinal());
        data.writeBoolean(filteredImport);
        data.writeVarInt(connections.size());
        for (var connection : connections) {
            data.writeResourceLocation(connection.dimension().location());
            data.writeBlockPos(connection.pos());
            data.writeByte(connection.boundFace().get3DDataValue());
        }
    }

    @Override
    protected boolean readFromStream(FriendlyByteBuf data) {
        boolean changed = super.readFromStream(data);
        var newProviderMode = enumByOrdinal(
                ProviderMode.values(), data.readByte(), ProviderMode.NORMAL);
        var newReturnMode = enumByOrdinal(
                ReturnMode.values(), data.readByte(), ReturnMode.OFF);
        var newDispatchMode = enumByOrdinal(
                WirelessDispatchMode.values(),
                data.readByte(),
                WirelessDispatchMode.EVEN_DISTRIBUTION);
        var newSpeedMode = enumByOrdinal(
                WirelessSpeedMode.values(),
                data.readByte(),
                WirelessSpeedMode.NORMAL);
        boolean newFilteredImport = data.readBoolean();
        int count = data.readVarInt();
        var newConnections = new ArrayList<WirelessConnection>(
                Math.min(count, MAX_WIRELESS_CONNECTIONS));
        for (int i = 0; i < count; i++) {
            var dimension = ResourceKey.create(
                    Registries.DIMENSION, data.readResourceLocation());
            var connection = new WirelessConnection(
                    dimension,
                    data.readBlockPos(),
                    Direction.from3DDataValue(data.readByte()));
            WirelessConnectionLists.addOrReplace(
                    newConnections, connection, MAX_WIRELESS_CONNECTIONS);
        }
        if (providerMode != newProviderMode
                || returnMode != newReturnMode
                || wirelessDispatchMode != newDispatchMode
                || wirelessSpeedMode != newSpeedMode
                || filteredImport != newFilteredImport
                || !connections.equals(newConnections)) {
            providerMode = newProviderMode;
            returnMode = newReturnMode;
            wirelessDispatchMode = newDispatchMode;
            wirelessSpeedMode = newSpeedMode;
            filteredImport = newFilteredImport;
            connections.clear();
            connections.addAll(newConnections);
            invalidConnectionScanCursor = 0;
            recomputeIdlePower();
            notifyLogicStateChanged();
            changed = true;
        }
        return changed;
    }

    private static <E> E enumByOrdinal(E[] values, int ordinal, E fallback) {
        return ordinal >= 0 && ordinal < values.length ? values[ordinal] : fallback;
    }

    @Override
    public void saveAdditional(CompoundTag data) {
        super.saveAdditional(data);
        data.putString(TAG_PROVIDER_MODE, providerMode.name());
        data.putString(TAG_RETURN_MODE, returnMode.name());
        data.putString(TAG_WIRELESS_DISPATCH_MODE, wirelessDispatchMode.name());
        data.putString(TAG_WIRELESS_SPEED_MODE, wirelessSpeedMode.name());
        data.putBoolean(TAG_FILTERED_IMPORT, filteredImport);
        data.put(TAG_CONNECTIONS, WirelessConnectionLists.writeTagList(connections));
    }

    @Override
    public void loadTag(CompoundTag data) {
        super.loadTag(data);
        var state = decodePersistentState(data);
        providerMode = state.providerMode();
        returnMode = state.returnMode();
        wirelessDispatchMode = state.wirelessDispatchMode();
        wirelessSpeedMode = state.wirelessSpeedMode();
        filteredImport = state.filteredImport();
        connections.clear();
        connections.addAll(state.connections());
        invalidConnectionScanCursor = 0;
        recomputeIdlePower();
        notifyLogicStateChanged();
    }

    static PersistentState decodePersistentState(CompoundTag data) {
        var providerMode = readEnum(
                data, TAG_PROVIDER_MODE, ProviderMode.class, ProviderMode.NORMAL);
        ReturnMode returnMode;
        if (data.contains(TAG_RETURN_MODE)) {
            returnMode = readEnum(
                    data, TAG_RETURN_MODE, ReturnMode.class, ReturnMode.OFF);
        } else if (data.contains(TAG_LEGACY_AUTO_RETURN)) {
            returnMode = data.getBoolean(TAG_LEGACY_AUTO_RETURN)
                    ? ReturnMode.AUTO
                    : ReturnMode.OFF;
        } else {
            returnMode = ReturnMode.OFF;
        }
        var dispatchMode = readEnum(
                data,
                TAG_WIRELESS_DISPATCH_MODE,
                WirelessDispatchMode.class,
                WirelessDispatchMode.EVEN_DISTRIBUTION);
        var speedMode = readEnum(
                data,
                TAG_WIRELESS_SPEED_MODE,
                WirelessSpeedMode.class,
                WirelessSpeedMode.NORMAL);
        var connections = new ArrayList<WirelessConnection>();
        WirelessConnectionLists.readTagList(
                data,
                TAG_CONNECTIONS,
                connections,
                MAX_WIRELESS_CONNECTIONS,
                WirelessConnection::fromTag);
        return new PersistentState(
                providerMode,
                returnMode,
                dispatchMode,
                speedMode,
                data.getBoolean(TAG_FILTERED_IMPORT),
                List.copyOf(connections));
    }

    private static <E extends Enum<E>> E readEnum(
            CompoundTag tag, String key, Class<E> type, E fallback) {
        if (!tag.contains(key)) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, tag.getString(key));
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }

    /**
     * Writes the frozen AE2LT-main provider settings into a host-owned
     * memory-card tag. The caller owns the data component carrying this tag.
     */
    protected final void writeStableMemoryCardSettings(CompoundTag tag) {
        writeMemoryCardState(
                tag,
                getProviderMode(),
                getReturnMode(),
                getWirelessDispatchMode(),
                getWirelessSpeedMode(),
                isFilteredImport());
    }

    static void writeMemoryCardState(
            CompoundTag tag,
            ProviderMode providerMode,
            ReturnMode returnMode,
            WirelessDispatchMode wirelessDispatchMode,
            WirelessSpeedMode wirelessSpeedMode,
            boolean filteredImport) {
        tag.putString(TAG_PROVIDER_MODE, providerMode.name());
        tag.putString(TAG_RETURN_MODE, returnMode.name());
        tag.putString(TAG_WIRELESS_DISPATCH_MODE, wirelessDispatchMode.name());
        tag.putString(TAG_WIRELESS_SPEED_MODE, wirelessSpeedMode.name());
        tag.putBoolean(TAG_FILTERED_IMPORT, filteredImport);
    }

    /**
     * Applies settings written by AE2LT main or
     * {@link #writeStableMemoryCardSettings(CompoundTag)}.
     */
    protected final void readStableMemoryCardSettings(CompoundTag tag) {
        if (tag.contains(TAG_PROVIDER_MODE)) {
            setProviderMode(readEnum(
                    tag,
                    TAG_PROVIDER_MODE,
                    ProviderMode.class,
                    getProviderMode()));
        }
        if (tag.contains(TAG_RETURN_MODE)) {
            setReturnMode(readEnum(
                    tag,
                    TAG_RETURN_MODE,
                    ReturnMode.class,
                    getReturnMode()));
        } else if (tag.contains(TAG_LEGACY_AUTO_RETURN)) {
            setReturnMode(tag.getBoolean(TAG_LEGACY_AUTO_RETURN)
                    ? ReturnMode.AUTO
                    : ReturnMode.OFF);
        }
        if (tag.contains(TAG_WIRELESS_DISPATCH_MODE)) {
            setWirelessDispatchMode(readEnum(
                    tag,
                    TAG_WIRELESS_DISPATCH_MODE,
                    WirelessDispatchMode.class,
                    getWirelessDispatchMode()));
        }
        if (tag.contains(TAG_WIRELESS_SPEED_MODE)) {
            setWirelessSpeedMode(readEnum(
                    tag,
                    TAG_WIRELESS_SPEED_MODE,
                    WirelessSpeedMode.class,
                    getWirelessSpeedMode()));
        }
        if (tag.contains(TAG_FILTERED_IMPORT)) {
            setFilteredImport(tag.getBoolean(TAG_FILTERED_IMPORT));
        }
    }

    @Override
    public PatternContainerGroup getTerminalGroup() {
        if (providerMode != ProviderMode.WIRELESS
                || hasCustomName()
                || !(level instanceof ServerLevel hostLevel)
                || connections.isEmpty()) {
            return super.getTerminalGroup();
        }

        var counts = new LinkedHashMap<PatternContainerGroup, Integer>();
        int maxDistance = WirelessPatternProviderPolicy.maxDistance();
        for (var connection : connections) {
            if (WirelessConnectionValidator.validate(
                    hostLevel,
                    worldPosition,
                    connection,
                    maxDistance) != WirelessConnectionValidator.Status.VALID) {
                continue;
            }
            var targetLevel = hostLevel.getServer().getLevel(connection.dimension());
            if (targetLevel == null || !targetLevel.isLoaded(connection.pos())) {
                continue;
            }
            var group = PatternContainerGroup.fromMachine(
                    targetLevel, connection.pos(), connection.boundFace());
            if (group != null) {
                counts.merge(group, 1, Integer::sum);
            }
        }
        PatternContainerGroup selected = null;
        int selectedCount = 0;
        for (var entry : counts.entrySet()) {
            if (entry.getValue() > selectedCount) {
                selected = entry.getKey();
                selectedCount = entry.getValue();
            }
        }
        return selected != null ? selected : super.getTerminalGroup();
    }

    @Override
    public BlockPos getProviderPos() {
        return getBlockPos();
    }

    @Override
    public boolean isWirelessProvider() {
        return getProviderMode() == ProviderMode.WIRELESS;
    }

    @Override
    public int getMaxWirelessConnections() {
        return MAX_WIRELESS_CONNECTIONS;
    }

    @Override
    public abstract AEItemKey getTerminalIcon();
}
