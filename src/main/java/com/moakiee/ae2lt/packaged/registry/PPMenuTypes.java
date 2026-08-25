package com.moakiee.ae2lt.packaged.registry;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

import com.moakiee.ae2lt.packaged.AE2LTPackagedProvider;
import com.moakiee.ae2lt.packaged.menu.PackagedPatternProviderMenu;

public final class PPMenuTypes {
    public static final DeferredRegister<MenuType<?>> MENU_TYPES =
            DeferredRegister.create(Registries.MENU, AE2LTPackagedProvider.MODID);

    public static final RegistryObject<MenuType<PackagedPatternProviderMenu>>
            PACKAGED_PATTERN_PROVIDER = MENU_TYPES.register(
                    "packaged_pattern_provider",
                    () -> PackagedPatternProviderMenu.TYPE);

    private PPMenuTypes() {
    }
}
