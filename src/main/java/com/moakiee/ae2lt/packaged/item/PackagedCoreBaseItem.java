package com.moakiee.ae2lt.packaged.item;

import java.util.function.Consumer;

import net.minecraft.world.item.Item;
import net.minecraftforge.client.extensions.common.IClientItemExtensions;

import com.moakiee.ae2lt.packaged.client.PackagedCoreClientExtensions;

/**
 * The generic crafting base shared by every per-machine packaged core.
 *
 * <p>It carries no adapter metadata, but it renders with the same layered
 * packaged-core model, and on 1.20.1 that renderer has to be published by the
 * item itself rather than by a client-extension registration event.
 */
public class PackagedCoreBaseItem extends Item {

    public PackagedCoreBaseItem(Properties props) {
        super(props);
    }

    @Override
    public void initializeClient(Consumer<IClientItemExtensions> consumer) {
        PackagedCoreClientExtensions.accept(consumer);
    }
}
