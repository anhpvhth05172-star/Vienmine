package org.veinmine.vienmine.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import org.veinmine.vienmine.Shape;
import org.veinmine.vienmine.network.VeinmineActivePayload;

public class VienmineClient implements ClientModInitializer {

    public static KeyMapping veinmineKey;
    public static KeyMapping menuKey;
    private static final KeyMapping.Category VIENMINE_CATEGORY =
            KeyMapping.Category.register(Identifier.fromNamespaceAndPath("vienmine", "main"));
    private static boolean lastSent = false;
    private static Shape localShape = Shape.SHAPELESS;

    public static Shape localShape() {
        return localShape;
    }

    public static void setLocalShape(Shape shape) {
        localShape = shape;
    }

    @Override
    public void onInitializeClient() {
        // Hold-to-veinmine hotkey, defaults to V (rebindable in Settings -> Controls).
        // InputConstants.getKey avoids hardcoding platform key codes (SDL/GLFW-proof).
        veinmineKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.vienmine.veinmine",
                InputConstants.Type.KEYBOARD,
                InputConstants.getKey("key.keyboard.v").getValue(),
                VIENMINE_CATEGORY
        ));

        // Press G to open the shape selection menu (FTB Ultimine style).
        menuKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.vienmine.menu",
                InputConstants.Type.KEYBOARD,
                InputConstants.getKey("key.keyboard.g").getValue(),
                VIENMINE_CATEGORY
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null) return;

            // Open shape menu on press.
            while (menuKey.consumeClick()) {
                if (client.gui != null) {
                    client.gui.setScreen(new ShapeSelectScreen());
                }
            }

            // Hold-state sync (only on change).
            boolean held;
            try {
                held = veinmineKey.isDown();
            } catch (Exception e) {
                return;
            }
            if (held != lastSent) {
                lastSent = held;
                try {
                    if (ClientPlayNetworking.canSend(VeinmineActivePayload.ID)) {
                        ClientPlayNetworking.send(new VeinmineActivePayload(held));
                    }
                } catch (Exception ignored) {
                    // Not in a server / handshake not done yet.
                }
            }
        });
    }
}
