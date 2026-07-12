package com.moakiee.ae2lt.packaged.client;

import java.util.stream.Stream;

import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;

import appeng.client.gui.style.StyleManager;

import com.moakiee.ae2lt.api.client.PatternProviderToolbarButtonHider;
import com.moakiee.ae2lt.packaged.AE2LTPackagedProvider;
import com.moakiee.ae2lt.packaged.item.PackagedCoreDefinition;
import com.moakiee.ae2lt.packaged.menu.PackagedPatternProviderMenu;
import com.moakiee.ae2lt.packaged.registry.PPItems;

@EventBusSubscriber(modid = AE2LTPackagedProvider.MODID, value = Dist.CLIENT)
public final class PPClientScreens {
    private PPClientScreens() {
    }

    @SubscribeEvent
    public static void registerScreens(RegisterMenuScreensEvent event) {
        registerHiddenToolbarButtons();
        event.register(PackagedPatternProviderMenu.TYPE, PPClientScreens::createPackagedPatternProviderScreen);
    }

    private static void registerHiddenToolbarButtons() {
        PatternProviderToolbarButtonHider.registerHiddenButtonClassName(
                PatternProviderToolbarButtonHider.EXTENDED_AE_PLUS_SERVER_SETTING_BUTTON);
    }

    @SubscribeEvent
    public static void registerItemExtensions(RegisterClientExtensionsEvent event) {
        var renderer = new PackagedCoreItemRenderer();
        event.registerItem(new IClientItemExtensions() {
            @Override
            public PackagedCoreItemRenderer getCustomRenderer() {
                return renderer;
            }
        }, Stream.concat(
                Stream.of((Item) PPItems.BASIC_PACKAGED_CORE.get()),
                PackagedCoreDefinition.all().stream()
                .map(definition -> (Item) definition.runtimeItem().get())
        ).toArray(Item[]::new));
    }

    @SubscribeEvent
    public static void wrapPackagedCoreItemModels(ModelEvent.ModifyBakingResult event) {
        var models = event.getModels();
        for (var definition : PackagedCoreDefinition.all()) {
            var key = ModelResourceLocation.inventory(ResourceLocation.fromNamespaceAndPath(
                    AE2LTPackagedProvider.MODID,
                    definition.itemId()));
            var model = models.get(key);
            if (model != null) {
                models.put(key, new PackagedCoreBakedModel(model));
            }
        }
    }

    private static PackagedPatternProviderScreen createPackagedPatternProviderScreen(
            PackagedPatternProviderMenu menu,
            Inventory inv,
            Component title) {
        var style = StyleManager.loadStyleDoc("/screens/packaged_pattern_provider.json");
        return new PackagedPatternProviderScreen(menu, inv, title, style);
    }
}
