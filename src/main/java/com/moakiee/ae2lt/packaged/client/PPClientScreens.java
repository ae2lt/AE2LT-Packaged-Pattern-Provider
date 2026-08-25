package com.moakiee.ae2lt.packaged.client;

import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ModelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

import appeng.client.gui.style.StyleManager;

import com.moakiee.ae2lt.api.client.PatternProviderToolbarButtonHider;
import com.moakiee.ae2lt.packaged.AE2LTPackagedProvider;
import com.moakiee.ae2lt.packaged.item.PackagedCoreDefinition;
import com.moakiee.ae2lt.packaged.menu.PackagedPatternProviderMenu;

@Mod.EventBusSubscriber(
        modid = AE2LTPackagedProvider.MODID,
        value = Dist.CLIENT,
        bus = Mod.EventBusSubscriber.Bus.MOD)
public final class PPClientScreens {
    private PPClientScreens() {
    }

    /**
     * 1.20.1 has no {@code RegisterMenuScreensEvent}; screens are bound during
     * client setup and {@link MenuScreens} is not thread-safe, so the
     * registration is queued onto the main thread.
     */
    @SubscribeEvent
    public static void registerScreens(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            registerHiddenToolbarButtons();
            MenuScreens.register(
                    PackagedPatternProviderMenu.TYPE,
                    PPClientScreens::createPackagedPatternProviderScreen);
        });
    }

    private static void registerHiddenToolbarButtons() {
        PatternProviderToolbarButtonHider.registerHiddenButtonClassName(
                PatternProviderToolbarButtonHider.EXTENDED_AE_PLUS_SMART_FEATURE_BUTTON);
    }

    // Item renderers are contributed by the items themselves through
    // Item#initializeClient on 1.20.1; see PackagedCoreClientExtensions.

    @SubscribeEvent
    public static void wrapPackagedCoreItemModels(ModelEvent.ModifyBakingResult event) {
        var models = event.getModels();
        for (var definition : PackagedCoreDefinition.all()) {
            var key = new ModelResourceLocation(
                    AE2LTPackagedProvider.MODID,
                    definition.itemId(),
                    "inventory");
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
