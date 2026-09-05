package com.moakiee.ae2lt.packaged.client;

import net.minecraft.resources.ResourceLocation;

/** Client prediction only; the server validates the live packaged host and adapter. */
public final class WirelessConnectorBlockOnlyTarget {
    private static final ResourceLocation SPIRIT_FIRE =
            ResourceLocation.fromNamespaceAndPath("occultism", "spirit_fire");

    private WirelessConnectorBlockOnlyTarget() {
    }

    public static boolean shouldSubmit(boolean providerSelected, ResourceLocation blockId,
            boolean hasBlockEntity) {
        // Keep this exact ID aligned with OccultismSpiritFireAdapter without loading it on the client.
        return providerSelected && !hasBlockEntity && SPIRIT_FIRE.equals(blockId);
    }
}
