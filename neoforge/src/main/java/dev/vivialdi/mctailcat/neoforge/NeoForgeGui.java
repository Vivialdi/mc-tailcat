package dev.vivialdi.mctailcat.neoforge;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;

/**
 * Adds a "+ Tailcat" button to the multiplayer list.
 *
 * <p>Client-only by annotation, so this class is never loaded on a dedicated
 * server, where the Minecraft client classes it references do not exist.
 */
@EventBusSubscriber(modid = "tailcat", value = Dist.CLIENT)
public final class NeoForgeGui {

    private NeoForgeGui() {
    }

    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof JoinMultiplayerScreen screen)) {
            return;
        }

        // Top right, in the header strip above the server list: the vanilla
        // button rows sit at height-52 and height-28 and the list starts
        // around y=32, so this is the one area free at any window size.
        event.addListener(Button.builder(
                        Component.literal("+ Tailcat"),
                        b -> Minecraft.getInstance().setScreen(new AddTailcatServerScreen(screen)))
                .bounds(screen.width - 84, 4, 80, 20)
                .build());
    }
}
