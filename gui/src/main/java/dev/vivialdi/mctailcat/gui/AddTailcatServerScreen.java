package dev.vivialdi.mctailcat.gui;

import dev.vivialdi.mctailcat.core.ClientConfig;
import dev.vivialdi.mctailcat.core.NetworkDescriptor;
import dev.vivialdi.mctailcat.core.TailcatClientRuntime;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import java.util.List;
import net.minecraft.util.Formatting;

/**
 * Where a player pastes the address an operator sent them.
 *
 * <p>Everything this screen does goes through {@link TailcatClientRuntime}, so
 * the outcome is identical to having put the address in the config file and
 * relaunched — except it takes effect now.
 */
public class AddTailcatServerScreen extends Screen {

    private final Screen parent;

    private TextFieldWidget nameField;
    private TextFieldWidget addressField;
    private ButtonWidget addButton;
    private Text error = Text.empty();
    private List<ClientConfig.Entry> typed = List.of();

    public AddTailcatServerScreen(Screen parent) {
        super(Text.literal("Add a Tailcat Server"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int centre = this.width / 2;
        int top = this.height / 4 + 12;

        this.nameField = new TextFieldWidget(this.textRenderer, centre - 100, top, 200, 20,
                Text.literal("Name"));
        this.nameField.setMaxLength(64);
        this.nameField.setText("Tailcat Server");
        this.addDrawableChild(this.nameField);

        this.addressField = new TextFieldWidget(this.textRenderer, centre - 100, top + 48, 200, 20,
                Text.literal("Address"));
        // Addresses are long, and people paste the whole line they were sent.
        this.addressField.setMaxLength(512);
        this.addressField.setChangedListener(text -> refresh());
        this.addDrawableChild(this.addressField);

        this.addButton = ButtonWidget.builder(Text.literal("Add"), button -> add())
                .dimensions(centre - 100, top + 96, 98, 20)
                .build();
        this.addDrawableChild(this.addButton);

        this.addDrawableChild(ButtonWidget.builder(Text.literal("Cancel"), button -> close())
                .dimensions(centre + 2, top + 96, 98, 20)
                .build());

        // Anything the player added themselves, with a way to take it back.
        // Without this a mistyped address is permanent: deleting the row only
        // lasts until the next launch, because the config puts it back.
        TailcatClientRuntime runtime = TailcatClientRuntime.current();
        this.typed = runtime == null ? List.of() : runtime.typedServers();
        int row = top + 132;
        for (ClientConfig.Entry entry : this.typed) {
            String address = entry.address;
            this.addDrawableChild(ButtonWidget.builder(Text.literal("Forget"), b -> forget(address))
                    .dimensions(centre + 42, row, 58, 20)
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
        this.error = Text.empty();
        // Rebuild so the row disappears with it.
        this.clearAndInit();
    }

    /**
     * The Add button means nothing until there is something to add.
     *
     * <p>Guarded because this runs from a text field listener, and a screen is
     * re-initialised on every window resize: a keystroke arriving mid-rebuild
     * should not take the game down.
     */
    private void refresh() {
        if (this.addButton != null && this.addressField != null) {
            this.addButton.active = !this.addressField.getText().isBlank();
        }
    }

    private void add() {
        TailcatClientRuntime runtime = TailcatClientRuntime.current();
        if (runtime == null) {
            this.error = Text.literal("Tailcat is not running — check your log.")
                    .formatted(Formatting.RED);
            return;
        }

        String problem = runtime.addServer(this.addressField.getText(), this.nameField.getText(),
                25565);
        if (problem.isEmpty()) {
            close();
        } else {
            this.error = Text.literal(problem).formatted(Formatting.RED);
        }
    }

    @Override
    public void close() {
        // Going back to the same MultiplayerScreen instance shows nothing new:
        // its list widget keeps its own copy of the entries, and re-initialising
        // the screen reuses that widget rather than rebuilding it, so reloading
        // the ServerList underneath changes nothing on display. Vanilla's own
        // Refresh button does not reload either -- it constructs a fresh screen.
        // Do the same. `parent` is private, hence the access widener.
        if (this.parent instanceof MultiplayerScreen multiplayer) {
            this.client.setScreen(new MultiplayerScreen(multiplayer.parent));
            return;
        }
        this.client.setScreen(this.parent);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        int centre = this.width / 2;
        int top = this.height / 4 + 12;

        context.drawCenteredTextWithShadow(this.textRenderer, this.title, centre, top - 32,
                0xFFFFFF);
        context.drawTextWithShadow(this.textRenderer, Text.literal("Name"), centre - 100,
                top - 12, 0xA0A0A0);
        context.drawTextWithShadow(this.textRenderer, Text.literal("Tailcat address"), centre - 100,
                top + 36, 0xA0A0A0);

        int row = top + 132;
        if (!this.typed.isEmpty()) {
            context.drawTextWithShadow(this.textRenderer,
                    Text.literal("Servers you added").formatted(Formatting.GRAY),
                    centre - 100, row - 12, 0xA0A0A0);
        }
        for (ClientConfig.Entry entry : this.typed) {
            if (row > this.height - 40) {
                break;
            }
            context.drawTextWithShadow(this.textRenderer, Text.literal(entry.name), centre - 100,
                    row + 6, 0xFFFFFF);
            row += 22;
        }

        if (!this.error.getString().isEmpty()) {
            context.drawCenteredTextWithShadow(this.textRenderer, this.error, centre, top + 72,
                    0xFF5555);
        } else {
            context.drawCenteredTextWithShadow(this.textRenderer, preview(), centre, top + 72,
                    0xA0A0A0);
        }
    }

    /**
     * What will actually be used, shown back to the player.
     *
     * <p>Nothing here can tell a real address from a mistyped one — an address
     * carries no checksum, so anything of the right shape is accepted and only
     * fails later, as a server that never responds. Showing the parsed result
     * and its length at least makes a truncated paste or a stray keystroke
     * visible before it becomes an entry that will never connect.
     */
    private Text preview() {
        String typed = this.addressField.getText();
        if (typed.isBlank()) {
            return Text.literal("Paste the address or the whole line you were sent.")
                    .formatted(Formatting.GRAY);
        }
        String address = NetworkDescriptor.findAddress(typed);
        if (address == null) {
            return Text.literal("No Tailcat address found in that text.")
                    .formatted(Formatting.RED);
        }
        String shown = address.length() <= 24
                ? address
                : address.substring(0, 12) + "..." + address.substring(address.length() - 8);
        return Text.literal(shown + "  (" + address.length() + " characters)")
                .formatted(Formatting.GRAY);
    }
}
