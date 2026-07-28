package com.moakiee.ae2lt.packaged.client;

import java.util.List;

import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

import appeng.api.config.ActionItems;
import appeng.api.config.Settings;
import appeng.client.gui.Icon;
import appeng.client.gui.implementations.PatternProviderScreen;
import appeng.client.gui.style.ScreenStyle;
import appeng.client.gui.widgets.ActionButton;
import appeng.client.gui.widgets.SettingToggleButton;
import appeng.client.gui.widgets.ToggleButton;
import appeng.client.gui.widgets.UpgradesPanel;

import com.moakiee.ae2lt.api.client.PatternProviderToolbarButtonHider;
import com.moakiee.ae2lt.api.frequency.FrequencyApi;
import com.moakiee.ae2lt.packaged.menu.PackagedPatternProviderMenu;

public class PackagedPatternProviderScreen
        extends PatternProviderScreen<PackagedPatternProviderMenu> {
    private final ToggleButton autoReturnButton;

    public PackagedPatternProviderScreen(PackagedPatternProviderMenu menu, Inventory playerInventory,
                                         Component title, ScreenStyle style) {
        super(menu, playerInventory, title, style);

        var frequencyButton = new ActionButton(
                ActionItems.TERMINAL_SETTINGS,
                () -> FrequencyApi.openBindingScreen(menu));
        frequencyButton.setMessage(
                Component.translatable("ae2ltpp.gui.frequency_binding"));
        addToLeftToolbar(frequencyButton);

        autoReturnButton = new ToggleButton(
                Icon.AUTO_EXPORT_ON,
                Icon.AUTO_EXPORT_OFF,
                ignored -> menu.clientToggleAutoReturn());
        autoReturnButton.setTooltipOn(List.of(
                Component.translatable("ae2ltpp.gui.auto_return.on")));
        autoReturnButton.setTooltipOff(List.of(
                Component.translatable("ae2ltpp.gui.auto_return.off")));
        addToLeftToolbar(autoReturnButton);

        var adapterSlot = menu.getAdapterSlot();
        if (adapterSlot != null) {
            widgets.add("adapterCard", new UpgradesPanel(List.of(adapterSlot), List::of));
        }
    }

    @Override
    protected void init() {
        super.init();
        hideUnsupportedToolbarButtons();
    }

    private void hideUnsupportedToolbarButtons() {
        for (var listener : children()) {
            if (!(listener instanceof AbstractWidget widget)) {
                continue;
            }
            boolean hide = PatternProviderToolbarButtonHider
                    .shouldHideToolbarButtonClassName(widget.getClass().getName());
            if (widget instanceof SettingToggleButton<?> settingButton) {
                var setting = settingButton.getSetting();
                hide |= setting == Settings.BLOCKING_MODE
                        || (setting != null
                        && PatternProviderToolbarButtonHider
                                .shouldHideToolbarButtonSettingName(setting.getName()));
            }
            if (hide) {
                widget.visible = false;
                widget.active = false;
            }
        }
    }

    @Override
    protected void updateBeforeRender() {
        super.updateBeforeRender();
        setTextContent(
                "dialog_title",
                Component.translatable(menu.getTitleTranslationKey()));
        autoReturnButton.setState(menu.isAutoReturnEnabled());
    }
}
