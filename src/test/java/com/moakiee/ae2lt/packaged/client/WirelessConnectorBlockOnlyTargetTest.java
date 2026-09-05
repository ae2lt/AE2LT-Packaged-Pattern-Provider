package com.moakiee.ae2lt.packaged.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import net.minecraft.resources.ResourceLocation;

import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

class WirelessConnectorBlockOnlyTargetTest {
    private static final String MIXINS = "src/main/java/com/moakiee/ae2lt/packaged/mixin/";
    private static final ResourceLocation SPIRIT_FIRE = id("occultism", "spirit_fire");

    @Test
    void providerSelectionCanSubmitSpiritFireWithoutALiveClientHost() throws IOException {
        assertTrue(WirelessConnectorBlockOnlyTarget.shouldSubmit(true, SPIRIT_FIRE, false));

        // An unloaded selected chunk must never be queried. Only the clicked target is read.
        var item = read(MIXINS + "WirelessConnectorItemMixin.java");
        assertFalse(item.contains("getSelectedProvider("));
        assertFalse(item.contains("StablePatternProviderBlockEntity"));
        assertTrue(item.contains("var pos = context.getClickedPos();"));
        assertTrue(item.contains("level.isLoaded(pos)"));
        assertTrue(item.contains("level.getBlockEntity(pos)"));
        assertTrue(item.contains("OverloadedWirelessConnectorItem.hasSelection(stack)"));
        assertTrue(item.contains("OverloadedWirelessConnectorItem.getSelectedHostType(stack)"));
        assertTrue(item.contains("OverloadedWirelessConnectorItem.HOST_PROVIDER.equals("));
        assertTrue(item.contains("OverloadedWirelessConnectorItem.isSelectionInCurrentDimension(level, stack)"));
        assertTrue(item.contains("WirelessConnectorBlockOnlyTarget.shouldSubmit(providerSelected,"));
    }

    @Test
    void noSelectionOrNonProviderSelectionDoesNotSubmitEvenSpiritFire() {
        assertFalse(WirelessConnectorBlockOnlyTarget.shouldSubmit(false, SPIRIT_FIRE, false));
    }

    @Test
    void ordinaryBlockOnlyTargetsRemainPass() {
        for (var path : new String[] { "air", "stone", "lever", "oak_door", "oak_trapdoor", "fire", "soul_fire" }) {
            assertFalse(WirelessConnectorBlockOnlyTarget.shouldSubmit(true, id("minecraft", path), false), path);
        }
        assertFalse(WirelessConnectorBlockOnlyTarget.shouldSubmit(true, id("othermod", "spirit_fire"), false));
        assertFalse(WirelessConnectorBlockOnlyTarget.shouldSubmit(true, id("occultism", "spirit_fire_extra"), false));
        assertFalse(WirelessConnectorBlockOnlyTarget.shouldSubmit(true, null, false));
    }

    @Test
    void blockEntityTargetsRemainInUpstreamHandling() {
        assertFalse(WirelessConnectorBlockOnlyTarget.shouldSubmit(true, SPIRIT_FIRE, true));
        assertFalse(WirelessConnectorBlockOnlyTarget.shouldSubmit(true, id("minecraft", "chest"), true));
    }

    @Test
    void clientRecognitionDoesNotLinkServerAdapterCode() throws IOException {
        var item = read(MIXINS + "WirelessConnectorItemMixin.java");
        var helper = read("src/main/java/com/moakiee/ae2lt/packaged/client/WirelessConnectorBlockOnlyTarget.java");
        for (var source : new String[] { item, helper }) {
            assertFalse(source.contains("import net.minecraft.server."));
            assertFalse(source.contains("import com.moakiee.ae2lt.packaged.logic.multiblock."));
            assertFalse(source.contains("MultiblockAdapterRegistry"));
        }
        var adapter = read("src/main/java/com/moakiee/ae2lt/packaged/logic/multiblock/occultism/OccultismSpiritFireAdapter.java");
        assertTrue(adapter.contains("MOD_ID = \"occultism\""));
        assertTrue(adapter.contains("SPIRIT_FIRE_BLOCK = occultismId(\"spirit_fire\")"));
        assertTrue(adapter.contains("blockId(state).equals(SPIRIT_FIRE_BLOCK)"));
    }

    @Test
    void itemIsClientOnlyAndPacketRetainsAuthoritativeCommonValidation() throws IOException {
        var config = JsonParser.parseString(read("src/main/resources/ae2ltpp.mixins.json")).getAsJsonObject();
        var item = new JsonPrimitive("WirelessConnectorItemMixin");
        var packet = new JsonPrimitive("WirelessConnectorPacketMixin");
        assertTrue(config.getAsJsonArray("client").contains(item));
        assertFalse(config.getAsJsonArray("mixins").contains(item));
        assertTrue(config.getAsJsonArray("mixins").contains(packet));
        assertFalse(config.getAsJsonArray("client").contains(packet));
        if (config.has("server")) {
            assertFalse(config.getAsJsonArray("server").contains(item));
        }

        var validator = read(MIXINS + "WirelessConnectorPacketMixin.java");
        assertTrue(validator.contains("OverloadedWirelessConnectorItem.hasSelection(stack)"));
        assertTrue(validator.contains("OverloadedWirelessConnectorItem.HOST_PROVIDER.equals("));
        assertTrue(validator.contains("OverloadedWirelessConnectorItem.isSelectionInCurrentDimension(level, stack)"));
        assertTrue(validator.contains("instanceof StablePatternProviderBlockEntity provider"));
        assertTrue(validator.contains("!provider.isWirelessProvider()"));
        assertTrue(validator.contains("MultiblockAdapterRegistry.find(level, pos(), null) == null"));
        assertTrue(validator.contains("return Set.of(pos.immutable());"));
        assertTrue(validator.contains("return WirelessConnectorTargetHelper.collectTargets(level, pos, contiguous);"));
    }

    private static ResourceLocation id(String namespace, String path) {
        return ResourceLocation.fromNamespaceAndPath(namespace, path);
    }

    private static String read(String path) throws IOException {
        return Files.readString(Path.of(path));
    }
}
