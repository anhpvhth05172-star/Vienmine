package org.veinmine.vienmine.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record VeinmineActivePayload(boolean active) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<VeinmineActivePayload> ID =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("vienmine", "active"));

    public static final StreamCodec<RegistryFriendlyByteBuf, VeinmineActivePayload> CODEC =
            StreamCodec.composite(ByteBufCodecs.BOOL, VeinmineActivePayload::active, VeinmineActivePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
