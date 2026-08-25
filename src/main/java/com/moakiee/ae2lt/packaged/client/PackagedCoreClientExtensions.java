package com.moakiee.ae2lt.packaged.client;

import java.util.function.Consumer;

import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraftforge.client.extensions.common.IClientItemExtensions;

/**
 * Client-side supplier for the packaged-core item renderer.
 *
 * <p>Forge 1.20.1 has no {@code RegisterClientExtensionsEvent}: an item hands
 * out its own client extensions from {@code Item#initializeClient}, which the
 * loader only ever calls on the client. The renderer itself is built on first
 * use rather than eagerly, because its constructor reaches into
 * {@code Minecraft.getInstance()} and must not run before the client is up.
 */
public final class PackagedCoreClientExtensions {

    private PackagedCoreClientExtensions() {
    }

    public static void accept(Consumer<IClientItemExtensions> consumer) {
        consumer.accept(LazyRenderer.INSTANCE);
    }

    private static final class LazyRenderer implements IClientItemExtensions {
        static final LazyRenderer INSTANCE = new LazyRenderer();

        private PackagedCoreItemRenderer renderer;

        @Override
        public BlockEntityWithoutLevelRenderer getCustomRenderer() {
            if (renderer == null) {
                renderer = new PackagedCoreItemRenderer();
            }
            return renderer;
        }
    }
}
