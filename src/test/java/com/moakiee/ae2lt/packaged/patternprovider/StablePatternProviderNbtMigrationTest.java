package com.moakiee.ae2lt.packaged.patternprovider;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.Level;

import com.moakiee.ae2lt.packaged.patternprovider.StablePatternProviderBlockEntity.ProviderMode;
import com.moakiee.ae2lt.packaged.patternprovider.StablePatternProviderBlockEntity.ReturnMode;
import com.moakiee.ae2lt.packaged.patternprovider.StablePatternProviderBlockEntity.WirelessConnection;
import com.moakiee.ae2lt.packaged.patternprovider.StablePatternProviderBlockEntity.WirelessDispatchMode;
import com.moakiee.ae2lt.packaged.patternprovider.StablePatternProviderBlockEntity.WirelessSpeedMode;

class StablePatternProviderNbtMigrationTest {

    /**
     * Touching {@code Registries} initializes {@code BuiltInRegistries}, which
     * refuses to build a registry before {@code Bootstrap} has run. 1.21 no
     * longer needs this; 1.20.1 does.
     */
    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void decodesTheMainBranchProviderSchemaWithoutRenamingFields() {
        var dimension = ResourceKey.create(
                Registries.DIMENSION,
                new ResourceLocation("migration", "factory"));
        var connection = new WirelessConnection(
                dimension, new BlockPos(12, 34, -56), Direction.WEST);

        var tag = new CompoundTag();
        tag.putString("OverloadMode", "WIRELESS");
        tag.putString("ReturnMode", "AUTO");
        tag.putString("WirelessDispatchMode", "SINGLE_TARGET");
        tag.putString("WirelessSpeedMode", "FAST");
        tag.putBoolean("FilteredImport", true);
        var connections = new ListTag();
        connections.add(connection.toTag());
        tag.put("WirelessConnections", connections);
        tag.putInt("FrequencyId", 42);

        var state = StablePatternProviderBlockEntity.decodePersistentState(tag);

        assertEquals(ProviderMode.WIRELESS, state.providerMode());
        assertEquals(ReturnMode.AUTO, state.returnMode());
        assertEquals(WirelessDispatchMode.SINGLE_TARGET, state.wirelessDispatchMode());
        assertEquals(WirelessSpeedMode.FAST, state.wirelessSpeedMode());
        assertEquals(true, state.filteredImport());
        assertEquals(java.util.List.of(connection), state.connections());
        // FrequencyId intentionally remains in the same root tag for the
        // AE2LT public FrequencyBindingAccess to restore independently.
        assertEquals(42, tag.getInt("FrequencyId"));
    }

    @Test
    void migratesTheLegacyAutoReturnBooleanAndPrefersTheNewEnum() {
        var legacy = new CompoundTag();
        legacy.putBoolean("AutoReturn", true);
        assertEquals(
                ReturnMode.AUTO,
                StablePatternProviderBlockEntity
                        .decodePersistentState(legacy)
                        .returnMode());

        legacy.putString("ReturnMode", "OFF");
        assertEquals(
                ReturnMode.OFF,
                StablePatternProviderBlockEntity
                        .decodePersistentState(legacy)
                        .returnMode());
    }

    @Test
    void memoryCardsKeepTheFrozenMainBranchFieldNames() {
        var tag = new CompoundTag();

        StablePatternProviderBlockEntity.writeMemoryCardState(
                tag,
                ProviderMode.WIRELESS,
                ReturnMode.AUTO,
                WirelessDispatchMode.SINGLE_TARGET,
                WirelessSpeedMode.FAST,
                true);

        assertEquals("WIRELESS", tag.getString("OverloadMode"));
        assertEquals("AUTO", tag.getString("ReturnMode"));
        assertEquals("SINGLE_TARGET", tag.getString("WirelessDispatchMode"));
        assertEquals("FAST", tag.getString("WirelessSpeedMode"));
        assertEquals(true, tag.getBoolean("FilteredImport"));

        var decoded = StablePatternProviderBlockEntity.decodePersistentState(tag);
        assertEquals(ProviderMode.WIRELESS, decoded.providerMode());
        assertEquals(ReturnMode.AUTO, decoded.returnMode());
        assertEquals(WirelessDispatchMode.SINGLE_TARGET, decoded.wirelessDispatchMode());
        assertEquals(WirelessSpeedMode.FAST, decoded.wirelessSpeedMode());
        assertEquals(true, decoded.filteredImport());
    }

    @Test
    void toleratesDevBranchExtrasAndMalformedEnums() {
        var tag = new CompoundTag();
        tag.putString("OverloadMode", "REMOVED_MODE");
        tag.putString("ReturnMode", "REMOVED_MODE");
        tag.putString("WirelessDispatchMode", "REMOVED_MODE");
        tag.putString("WirelessSpeedMode", "REMOVED_MODE");
        tag.putString("BlockingMode", "SAME_PATTERN");
        tag.putBoolean("AdaptiveBatchEnabled", true);
        tag.put("ae2lt:wireless_overflow", new CompoundTag());
        tag.put("ae2lt:restore_overflow", new ListTag());

        var state = StablePatternProviderBlockEntity.decodePersistentState(tag);

        assertEquals(ProviderMode.NORMAL, state.providerMode());
        assertEquals(ReturnMode.OFF, state.returnMode());
        assertEquals(
                WirelessDispatchMode.EVEN_DISTRIBUTION,
                state.wirelessDispatchMode());
        assertEquals(WirelessSpeedMode.NORMAL, state.wirelessSpeedMode());
        assertEquals(false, state.filteredImport());
        assertEquals(java.util.List.of(), state.connections());
    }
}
