package com.moakiee.ae2lt.packaged.testsupport;

import java.util.ArrayList;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraftforge.network.NetworkConstants;
import net.minecraftforge.network.NetworkEvent;

/** Minecraft registry bootstrap for plain JUnit without ModLauncher event transformation. */
public final class MinecraftTestBootstrap {
    private MinecraftTestBootstrap() {
    }

    public static void initialize() {
        // A failed class initializer stays failed, unlike Bootstrap.bootStrap(),
        // which sets its initialized flag before all Forge hooks have completed.
        if (!Initialized.READY) {
            throw new IllegalStateException("Minecraft test bootstrap did not complete");
        }
    }

    private static final class Initialized {
        private static final boolean READY = bootstrap();

        private static boolean bootstrap() {
            SharedConstants.tryDetectVersion();
            // EventListenerHelper otherwise constructs these classes via a no-arg
            // constructor supplied only by the absent Forge transformer. The public
            // instance API populates the same listener cache without that constructor.
            new NetworkEvent(() -> null).getListenerList();
            new NetworkEvent.GatherLoginPayloadsEvent(new ArrayList<>(), false).getListenerList();
            Bootstrap.bootStrap();
            // Detect an earlier failed Bootstrap call even if its flag was already set.
            NetworkConstants.init();
            return true;
        }
    }
}
