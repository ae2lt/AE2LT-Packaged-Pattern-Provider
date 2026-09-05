package com.moakiee.ae2lt.packaged.testsupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.ArrayList;

import org.junit.jupiter.api.Test;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Items;
import net.minecraftforge.eventbus.api.EventListenerHelper;
import net.minecraftforge.network.NetworkConstants;
import net.minecraftforge.network.NetworkEvent;

class MinecraftTestBootstrapTest {
    @Test
    void completesForgeNetworkingAndRegistriesAndCanBeCalledRepeatedly() {
        MinecraftTestBootstrap.initialize();
        MinecraftTestBootstrap.initialize();

        assertEquals("FML3", NetworkConstants.init());
        assertSame(Items.DIAMOND, BuiltInRegistries.ITEM.get(
                ResourceLocation.fromNamespaceAndPath("minecraft", "diamond")));
        assertSame(new NetworkEvent(() -> null).getListenerList(),
                EventListenerHelper.getListenerList(NetworkEvent.class));
        assertSame(new NetworkEvent.GatherLoginPayloadsEvent(new ArrayList<>(), false).getListenerList(),
                EventListenerHelper.getListenerList(NetworkEvent.GatherLoginPayloadsEvent.class));
    }
}
