package dev.vivialdi.mctailcat.gui;

import dev.vivialdi.mctailcat.core.TailcatClientRuntime;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
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

        this.setInitialFocus(this.addressField);
        refresh();
    }

    /** The Add button means nothing until there is something to add. */
    private void refresh() {
        this.addButton.active = !this.addressField.getText().isBlank();
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
        // Back to the multiplayer list, which re-reads servers.dat as it opens,
        // so a server added here is in the list the moment the player returns.
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

        if (this.error.getString().isEmpty()) {
            context.drawCenteredTextWithShadow(this.textRenderer,
                    Text.literal("Paste the address or the whole line you were sent.")
                            .formatted(Formatting.GRAY),
                    centre, top + 72, 0xA0A0A0);
        } else {
            context.drawCenteredTextWithShadow(this.textRenderer, this.error, centre, top + 72,
                    0xFF5555);
        }
    }
}
