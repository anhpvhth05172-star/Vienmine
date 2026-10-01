package org.veinmine.vienmine.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;
import org.veinmine.vienmine.network.VeinmineActivePayload;

public class VienmineClient implements ClientModInitializer {

    public static KeyBinding veinmineKey;
    private static boolean lastSent = false;

    @Override
    public void onInitializeClient() {
        // Hotkey GIỮ để dùng veinmine, mặc định phím V (đổi được trong Settings -> Controls)
        veinmineKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.vienmine.veinmine",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_V,
                "category.vienmine"
        ));

        // Mỗi tick: nếu trạng thái giữ/thả đổi thì báo cho server
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null) return;
            boolean held;
            try {
                held = veinmineKey.isPressed();
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
                    // Chưa vào server / chưa handshake xong
                }
            }
        });
    }
}
