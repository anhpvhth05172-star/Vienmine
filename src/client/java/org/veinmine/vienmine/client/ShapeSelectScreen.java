package org.veinmine.vienmine.client;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.veinmine.vienmine.Shape;
import org.veinmine.vienmine.network.VeinmineShapePayload;

/** Shape selection menu (FTB Ultimine style): press G, click a shape, done. */
public class ShapeSelectScreen extends Screen {

    private static final int BUTTON_W = 160;
    private static final int BUTTON_H = 20;
    private static final int GAP = 6;

    public ShapeSelectScreen() {
        super(Component.translatable("vienmine.menu.title"));
    }

    @Override
    protected void init() {
        super.init();
        Shape current = VienmineClient.localShape();
        int totalH = Shape.values().length * (BUTTON_H + GAP) - GAP;
        int y = this.height / 2 - totalH / 2;

        for (Shape shape : Shape.values()) {
            final Shape s = shape;
            String label = (s == current ? "> " : "") + Component.translatable(s.langKey()).getString()
                    + (s == current ? " <" : "");
            this.addRenderableWidget(Button.builder(Component.literal(label), b -> select(s))
                    .bounds(this.width / 2 - BUTTON_W / 2, y, BUTTON_W, BUTTON_H)
                    .build());
            y += BUTTON_H + GAP;
        }
    }

    private void select(Shape shape) {
        VienmineClient.setLocalShape(shape);
        try {
            ClientPlayNetworking.send(new VeinmineShapePayload(shape.ordinal()));
        } catch (Exception ignored) {
        }
        if (this.minecraft != null && this.minecraft.player != null) {
            this.minecraft.player.sendSystemMessage(Component.translatable(shape.langKey()));
        }
        this.onClose();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
