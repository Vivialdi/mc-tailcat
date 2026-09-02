package dev.vivialdi.mctailcat.neoforge;

import dev.vivialdi.mctailcat.core.ClientConfig;
import dev.vivialdi.mctailcat.core.NetworkDescriptor;
import dev.vivialdi.mctailcat.core.TailcatClientRuntime;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.network.chat.Component;

/**
 * Where a player pastes the address an operator sent them.
 *
 * <p>The NeoForge twin of the Fabric companion's screen: same layout, same
 * behaviour, Mojang class names. Everything it does goes through
 * {@link TailcatClientRuntime}, so the outcome is identical to having put the
 * address in the config file and relaunched — except it takes effect now.
 */
public class AddTailcatServerScreen extends Screen {

    private final Screen parent;

    private EditBox nameField;
    private EditBox addressField;
    private Button addButton;
    private Component error = Component.empty();
    private List<ClientConfig.Entry> typed = List.of();

    public AddTailcatServerScreen(Screen parent) {
        super(Component.literal("Add a Tailcat Server"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int centre = this.width / 2;
        int top = this.height / 4 + 12;

        this.nameField = new EditBox(this.font, centre - 100, top, 200, 20,
                Component.literal("Name"));
        this.nameField.setMaxLength(64);
        this.nameField.setValue("Tailcat Server");
        this.addRenderableWidget(this.nameField);

        this.addressField = new EditBox(this.font, centre - 100, top + 48, 200, 20,
                Component.literal("Address"));
        // Addresses are long, and people paste the whole line they were sent.
        this.addressField.setMaxLength(512);
        this.addressField.setResponder(text -> refresh());
        this.addRenderableWidget(this.addressField);

        this.addButton = Button.builder(Component.literal("Add"), button -> add())
                .bounds(centre - 100, top + 96, 98, 20)
                .build();
        this.addRenderableWidget(this.addButton);

        this.addRenderableWidget(Button.builder(Component.literal("Cancel"), button -> onClose())
                .bounds(centre + 2, top + 96, 98, 20)
                .build());

        // Anything the player added themselves, with a way to take it back.
        // Without this a mistyped address is permanent: deleting the row only
        // lasts until the next launch, because the config puts it back.
        TailcatClientRuntime runtime = TailcatClientRuntime.current();
        this.typed = runtime == null ? List.of() : runtime.typedServers();
        int row = top + 132;
        for (ClientConfig.Entry entry : this.typed) {
            String address = entry.address;
            this.addRenderableWidget(Button.builder(Component.literal("Forget"), b -> forget(address))
                    .bounds(centre + 42, row, 58, 20)
                    .build());
            row += 22;
            if (row > this.height - 40) {
                break;
            }
        }

        this.setInitialFocus(this.addressField);
        refresh();
    }

    private void forget(String address) {
        TailcatClientRuntime runtime = TailcatClientRuntime.current();
        if (runtime != null) {
            runtime.removeServer(address);
        }
        this.error = Component.empty();
        // Rebuild so the row disappears with it.
        this.rebuildWidgets();
    }

    /**
     * The Add button means nothing until there is something to add. Guarded
     * because this runs from a text field responder, and a screen is
     * re-initialised on every window resize.
     */
    private void refresh() {
        if (this.addButton != null && this.addressField != null) {
            this.addButton.active = !this.addressField.getValue().isBlank();
        }
    }

    private void add() {
        TailcatClientRuntime runtime = TailcatClientRuntime.current();
        if (runtime == null) {
            this.error = Component.literal("Tailcat is not running — check your log.")
                    .withStyle(ChatFormatting.RED);
            return;
        }

        String problem = runtime.addServer(this.addressField.getValue(), this.nameField.getValue(),
                25565);
        if (problem.isEmpty()) {
            onClose();
        } else {
            this.error = Component.literal(problem).withStyle(ChatFormatting.RED);
        }
    }

    @Override
    public void onClose() {
        // Going back to the same JoinMultiplayerScreen instance shows nothing
        // new: its list widget keeps its own copy of the entries. Vanilla's
        // Refresh button constructs a fresh screen; do the same. `lastScreen`
        // is private, hence the access transformer.
        if (this.parent instanceof JoinMultiplayerScreen multiplayer) {
            this.minecraft.setScreen(new JoinMultiplayerScreen(multiplayer.lastScreen));
            return;
        }
        this.minecraft.setScreen(this.parent);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        int centre = this.width / 2;
        int top = this.height / 4 + 12;

        graphics.drawCenteredString(this.font, this.title, centre, top - 32, 0xFFFFFF);
        graphics.drawString(this.font, Component.literal("Name"), centre - 100, top - 12, 0xA0A0A0);
        graphics.drawString(this.font, Component.literal("Tailcat address"), centre - 100, top + 36,
                0xA0A0A0);

        int row = top + 132;
        if (!this.typed.isEmpty()) {
            graphics.drawString(this.font,
                    Component.literal("Servers you added").withStyle(ChatFormatting.GRAY),
                    centre - 100, row - 12, 0xA0A0A0);
        }
        for (ClientConfig.Entry entry : this.typed) {
            if (row > this.height - 40) {
                break;
            }
            graphics.drawString(this.font, Component.literal(entry.name), centre - 100, row + 6,
                    0xFFFFFF);
            row += 22;
        }

        if (!this.error.getString().isEmpty()) {
            graphics.drawCenteredString(this.font, this.error, centre, top + 72, 0xFF5555);
        } else {
            graphics.drawCenteredString(this.font, preview(), centre, top + 72, 0xA0A0A0);
        }
    }

    /**
     * What will actually be used, shown back to the player. An address carries
     * no checksum, so anything of the right shape is accepted; showing the
     * parsed result and its length at least makes a truncated paste visible.
     */
    private Component preview() {
        String text = this.addressField.getValue();
        if (text.isBlank()) {
            return Component.literal("Paste the address or the whole line you were sent.")
                    .withStyle(ChatFormatting.GRAY);
        }
        String address = NetworkDescriptor.findAddress(text);
        if (address == null) {
            return Component.literal("No Tailcat address found in that text.")
                    .withStyle(ChatFormatting.RED);
        }
        String shown = address.length() <= 24
                ? address
                : address.substring(0, 12) + "..." + address.substring(address.length() - 8);
        return Component.literal(shown + "  (" + address.length() + " characters)")
                .withStyle(ChatFormatting.GRAY);
    }
}
