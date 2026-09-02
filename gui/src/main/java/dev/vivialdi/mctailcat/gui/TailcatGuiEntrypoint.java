package dev.vivialdi.mctailcat.gui;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

/**
 * Adds a "Add Tailcat Server" button to the multiplayer list.
 *
 * <p>Uses Fabric API's screen events rather than a mixin. Both are tied to a
 * Minecraft version, but an event listener degrades more gracefully than a
 * patched method: if the screen's layout changes, the button lands somewhere
 * odd instead of the game failing to start.
 */
public class TailcatGuiEntrypoint implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (!(screen instanceof MultiplayerScreen)) {
                return;
            }

            // Top right, in the header strip above the server list. The two
            // vanilla button rows sit at height-52 and height-28 and the list
            // starts around y=32, so this is the one area guaranteed to be
            // free at any window size.
            ButtonWidget button = ButtonWidget.builder(
                            Text.literal("+ Tailcat"),
                            b -> client.setScreen(new AddTailcatServerScreen(screen)))
                    .dimensions(width - 84, 4, 80, 20)
                    .build();

            Screens.getButtons(screen).add(button);
        });
    }
}
