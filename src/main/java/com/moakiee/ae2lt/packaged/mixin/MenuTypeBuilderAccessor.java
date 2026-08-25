package com.moakiee.ae2lt.packaged.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MenuType;

import appeng.menu.AEBaseMenu;
import appeng.menu.implementations.MenuTypeBuilder;
import appeng.menu.locator.MenuLocator;

/**
 * Exposes {@link MenuTypeBuilder}'s internals so 1.21's
 * {@code buildUnregistered(ResourceLocation)} can be reproduced on 1.20.1.
 *
 * <p>1.20.1's {@code build(String)} forces the {@code ae2:} namespace and queues
 * the type into AE2's own registration list; this addon registers its menu type
 * through {@code PPMenuTypes} instead, so it needs the builder's wiring without
 * that side effect.
 */
@Mixin(MenuTypeBuilder.class)
public interface MenuTypeBuilderAccessor<M extends AEBaseMenu, I> {
    @Accessor(value = "id", remap = false)
    void ae2ltpp$setId(ResourceLocation id);

    @Accessor(value = "menuType", remap = false)
    void ae2ltpp$setMenuType(MenuType<M> menuType);

    @Accessor(value = "menuType", remap = false)
    MenuType<M> ae2ltpp$getMenuType();

    @Invoker(value = "fromNetwork", remap = false)
    M ae2ltpp$invokeFromNetwork(int containerId, Inventory inv, FriendlyByteBuf packetBuf);

    @Invoker(value = "open", remap = false)
    boolean ae2ltpp$invokeOpen(Player player, MenuLocator locator, boolean fromSubMenu);
}
