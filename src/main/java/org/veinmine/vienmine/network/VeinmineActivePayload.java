package org.veinmine.vienmine.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public record VeinmineActivePayload(boolean active) implements CustomPayload {
    public static final CustomPayload.Id<VeinmineActivePayload> ID =
            new CustomPayload.Id<>(Identifier.of("vienmine", "active"));

    public static final PacketCodec<RegistryByteBuf, VeinmineActivePayload> CODEC =
            PacketCodec.tuple(PacketCodecs.BOOLEAN, VeinmineActivePayload::active, VeinmineActivePayload::new);

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
