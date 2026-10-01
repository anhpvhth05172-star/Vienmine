package org.veinmine.vienmine;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.veinmine.vienmine.network.VeinmineActivePayload;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class Vienmine implements ModInitializer {

    public static final String MODID = "vienmine";

    // Max extra blocks per vein (anti-lag / anti-abuse). Change as you like.
    public static final int MAX_BLOCKS = 64;
    // BFS visit cap so we never scan the whole map.
    private static final int MAX_SEARCH = 512;
    // Max radius from the first broken block.
    private static final int RADIUS = 8;

    // UUIDs of players currently HOLDING the veinmine key (synced from client).
    private static final Set<UUID> ACTIVE = ConcurrentHashMap.newKeySet();

    // Guard against recursion: destroyBlock inside AFTER fires AFTER again.
    private static final ThreadLocal<Boolean> GUARD = ThreadLocal.withInitial(() -> false);

    public static boolean isActive(UUID id) {
        return ACTIVE.contains(id);
    }

    @Override
    public void onInitialize() {
        // Register client->server payload: key held state.
        PayloadTypeRegistry.serverboundPlay().register(VeinmineActivePayload.ID, VeinmineActivePayload.CODEC);

        ServerPlayNetworking.registerGlobalReceiver(VeinmineActivePayload.ID, (payload, context) -> {
            UUID id = context.player().getUUID();
            if (payload.active()) {
                ACTIVE.add(id);
            } else {
                ACTIVE.remove(id);
            }
        });

        // Cleanup on disconnect.
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                ACTIVE.remove(handler.getPlayer().getUUID()));

        // Break 1 block -> also break connected blocks of the SAME type.
        PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, blockEntity) ->
                onBlockBroken(world, player, pos, state));
    }

    private void onBlockBroken(Level world, Player player, BlockPos originPos, BlockState originState) {
        if (world.isClientSide()) return;
        if (GUARD.get()) return;
        if (player.isCreative() || player.isSpectator()) return;
        if (!ACTIVE.contains(player.getUUID())) return; // key NOT held -> normal mine
        if (!(player instanceof ServerPlayer serverPlayer)) return;

        Block originBlock = originState.getBlock();

        // BFS in 26 directions (including diagonals) for ore / stone veins.
        Set<BlockPos> visited = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        queue.add(originPos.immutable());
        visited.add(originPos.immutable());

        int broken = 0;
        int searched = 0;

        GUARD.set(true);
        try {
            while (!queue.isEmpty() && broken < MAX_BLOCKS && searched < MAX_SEARCH) {
                BlockPos current = queue.poll();
                searched++;

                for (int dx = -1; dx <= 1; dx++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            if (dx == 0 && dy == 0 && dz == 0) continue;
                            BlockPos next = current.offset(dx, dy, dz);

                            // Radius limit (manual squared distance).
                            double ddx = next.getX() - originPos.getX();
                            double ddy = next.getY() - originPos.getY();
                            double ddz = next.getZ() - originPos.getZ();
                            if (ddx * ddx + ddy * ddy + ddz * ddz > (double) RADIUS * RADIUS) continue;
                            if (!visited.add(next.immutable())) continue;
                            if (searched++ > MAX_SEARCH) break;

                            BlockState targetState;
                            try {
                                targetState = world.getBlockState(next);
                            } catch (Exception e) {
                                continue;
                            }
                            if (targetState.isAir()) continue;
                            if (!targetState.is(originBlock)) continue;

                            queue.add(next.immutable());

                            // Origin already broken, only break the spread.
                            if (next.equals(originPos)) continue;
                            if (broken >= MAX_BLOCKS) break;

                            // Stop if the held tool is gone (broke from durability).
                            if (player.getMainHandItem().isEmpty()) {
                                queue.clear();
                                break;
                            }

                            // Full vanilla break: drops (Fortune/Silk Touch), tool damage, stats.
                            boolean ok = serverPlayer.gameMode.destroyBlock(next);
                            if (ok) {
                                broken++;
                                player.causeFoodExhaustion(0.005f);
                            }
                        }
                    }
                }
            }
        } finally {
            GUARD.set(false);
        }
    }
}
