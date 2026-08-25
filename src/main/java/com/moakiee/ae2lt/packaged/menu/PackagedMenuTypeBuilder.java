package com.moakiee.ae2lt.packaged.menu;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.MenuType;
import net.minecraftforge.common.extensions.IForgeMenuType;

import appeng.menu.AEBaseMenu;
import appeng.menu.MenuOpener;
import appeng.menu.implementations.MenuTypeBuilder;

import com.moakiee.ae2lt.packaged.mixin.MenuTypeBuilderAccessor;

/**
 * Reproduces 1.21's {@code MenuTypeBuilder.buildUnregistered(ResourceLocation)}
 * on Forge 1.20.1.
 *
 * <p>1.20.1 only offers {@code build(String)}, which forces the {@code ae2:}
 * namespace and queues the menu type into AE2's own registration list. This
 * addon owns its menu type and registers it through {@code PPMenuTypes}, so the
 * builder's network factory and menu opener are wired up here directly via
 * {@link MenuTypeBuilderAccessor}.
 */
public final class PackagedMenuTypeBuilder {

    private PackagedMenuTypeBuilder() {
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static <M extends AEBaseMenu, I> MenuType<M> buildUnregistered(
            MenuTypeBuilder<M, I> builder, ResourceLocation id) {
        MenuTypeBuilderAccessor<M, I> accessor = (MenuTypeBuilderAccessor) (Object) builder;
        if (accessor.ae2ltpp$getMenuType() != null) {
            throw new IllegalStateException("buildUnregistered already called for " + id);
        }
        accessor.ae2ltpp$setId(id);
        MenuType<M> menuType = IForgeMenuType.create(
                (containerId, inv, buf) -> fromNetwork(accessor, containerId, inv, buf));
        accessor.ae2ltpp$setMenuType(menuType);
        MenuOpener.addOpener(menuType, accessor::ae2ltpp$invokeOpen);
        return menuType;
    }

    private static <M extends AEBaseMenu, I> M fromNetwork(
            MenuTypeBuilderAccessor<M, I> accessor,
            int containerId,
            Inventory inv,
            FriendlyByteBuf buf) {
        return accessor.ae2ltpp$invokeFromNetwork(containerId, inv, buf);
    }
}
