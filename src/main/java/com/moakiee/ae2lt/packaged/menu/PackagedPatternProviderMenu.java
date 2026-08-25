package com.moakiee.ae2lt.packaged.menu;

import java.util.List;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import appeng.api.inventories.InternalInventory;
import appeng.helpers.patternprovider.PatternProviderLogicHost;
import appeng.menu.guisync.GuiSync;
import appeng.menu.implementations.PatternProviderMenu;
import appeng.menu.implementations.MenuTypeBuilder;
import appeng.menu.slot.AppEngSlot;

import com.moakiee.ae2lt.api.frequency.FrequencyBindingMenuHost;
import com.moakiee.ae2lt.packaged.AE2LTPackagedProvider;
import com.moakiee.ae2lt.packaged.blockentity.PackagedPatternProviderBlockEntity;
import com.moakiee.ae2lt.packaged.item.MultiblockAdapterItem;
import com.moakiee.ae2lt.packaged.patternprovider.StablePatternProviderBlockEntity.ReturnMode;

public class PackagedPatternProviderMenu extends PatternProviderMenu
        implements FrequencyBindingMenuHost {
    public static final MenuType<PackagedPatternProviderMenu> TYPE =
            PackagedMenuTypeBuilder.buildUnregistered(
                    MenuTypeBuilder.create(
                            PackagedPatternProviderMenu::new, PatternProviderLogicHost.class),
                    new ResourceLocation(
                            AE2LTPackagedProvider.MODID, "packaged_pattern_provider"));

    @GuiSync(22000)
    public int returnMode;

    @GuiSync(22001)
    public String titleTranslationKey =
            "ae2ltpp.gui.title.packaged_pattern_provider";

    private final PackagedPatternProviderBlockEntity host;
    private final Slot adapterSlot;

    public PackagedPatternProviderMenu(int id, Inventory playerInventory, PatternProviderLogicHost host) {
        super(TYPE, id, playerInventory, host);

        if (!(host instanceof PackagedPatternProviderBlockEntity packagedHost)) {
            throw new IllegalArgumentException("Packaged provider menu opened for non-packaged host: " + host);
        }
        this.host = packagedHost;

        var adapterSlot = new AdapterCardSlot(packagedHost.getAdapterInv(), 0);
        adapterSlot.setNotDraggable();
        adapterSlot.setEmptyTooltip(() -> List.of(Component.translatable("ae2ltpp.gui.adapter_slot")));
        this.adapterSlot = addSlot(adapterSlot, PackagedPatternProviderSlotSemantics.ADAPTER_CARD);

        registerClientAction("toggleAutoReturn", this::toggleAutoReturn);
    }

    public Slot getAdapterSlot() {
        return adapterSlot;
    }

    @Override
    public void broadcastChanges() {
        if (isServerSide()) {
            returnMode = host.getReturnMode().ordinal();
            titleTranslationKey = host.ae2lt$titleTranslationKey();
        }
        super.broadcastChanges();
    }

    private void toggleAutoReturn() {
        if (isServerSide()) {
            host.setReturnMode(
                    host.getReturnMode() == ReturnMode.AUTO
                            ? ReturnMode.OFF
                            : ReturnMode.AUTO);
        }
    }

    public void clientToggleAutoReturn() {
        sendClientAction("toggleAutoReturn");
    }

    public boolean isAutoReturnEnabled() {
        return returnMode == ReturnMode.AUTO.ordinal();
    }

    public String getTitleTranslationKey() {
        return titleTranslationKey;
    }

    private static final class AdapterCardSlot extends AppEngSlot {
        private AdapterCardSlot(InternalInventory inv, int invSlot) {
            super(inv, invSlot);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return stack.getItem() instanceof MultiblockAdapterItem && super.mayPlace(stack);
        }

        @Override
        public int getMaxStackSize() {
            return 1;
        }
    }
}
