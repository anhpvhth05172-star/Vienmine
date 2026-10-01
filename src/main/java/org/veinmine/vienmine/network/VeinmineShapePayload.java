package org.veinmine.vienmine.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record VeinmineShapePayload(int shape) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<VeinmineShapePayload> ID =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("vienmine", "shape"));

    public static final StreamCodec<RegistryFriendlyByteBuf, VeinmineShapePayload> CODEC =
            StreamCodec.composite(ByteBufCodecs.VAR_INT, VeinmineShapePayload::shape, VeinmineShapePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
