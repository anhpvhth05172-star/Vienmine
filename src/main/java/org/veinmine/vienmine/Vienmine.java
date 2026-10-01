package org.veinmine.vienmine;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
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

    // Guard against recursion: breakBlock inside AFTER fires AFTER again.
    private static final ThreadLocal<Boolean> GUARD = ThreadLocal.withInitial(() -> false);

    public static boolean isActive(UUID id) {
        return ACTIVE.contains(id);
    }

    @Override
    public void onInitialize() {
        // Register client->server payload: key held state.
        PayloadTypeRegistry.playC2S().register(VeinmineActivePayload.ID, VeinmineActivePayload.CODEC);

        ServerPlayNetworking.registerGlobalReceiver(VeinmineActivePayload.ID, (payload, context) -> {
            UUID id = context.player().getUuid();
            if (payload.active()) {
                ACTIVE.add(id);
            } else {
                ACTIVE.remove(id);
            }
        });

        // Cleanup on disconnect.
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                ACTIVE.remove(handler.getPlayer().getUuid()));

        // Break 1 block -> also break connected blocks of the SAME type.
        PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, blockEntity) ->
                onBlockBroken(world, player, pos, state));
    }

    private void onBlockBroken(World world, PlayerEntity player, BlockPos originPos, BlockState originState) {
        if (world.isClient()) return;
        if (GUARD.get()) return;
        if (player.isCreative() || player.isSpectator()) return;
        if (!ACTIVE.contains(player.getUuid())) return; // key NOT held -> normal mine
        if (!(world instanceof ServerWorld serverWorld)) return;
        if (!(player instanceof ServerPlayerEntity serverPlayer)) return;

        Block originBlock = originState.getBlock();
        ItemStack tool = player.getMainHandStack();

        // BFS in 26 directions (including diagonals) for ore / stone veins.
        Set<BlockPos> visited = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        queue.add(originPos.toImmutable());
        visited.add(originPos.toImmutable());

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
                            BlockPos next = current.add(dx, dy, dz);

                            // Radius limit.
                            if (next.getSquaredDistance(originPos) > (double) RADIUS * RADIUS) continue;
                            if (!visited.add(next.toImmutable())) continue;
                            if (searched++ > MAX_SEARCH) break;

                            BlockState targetState;
                            try {
                                targetState = world.getBlockState(next);
                            } catch (Exception e) {
                                continue;
                            }
                            if (targetState.isAir()) continue;
                            if (!targetState.isOf(originBlock)) continue;

                            queue.add(next.toImmutable());

                            // Origin already broken, only break the spread.
                            if (next.equals(originPos)) continue;
                            if (broken >= MAX_BLOCKS) break;

                            // Stop if tool is about to break.
                            if (!tool.isEmpty() && tool.isDamageable()
                                    && tool.getDamage() >= tool.getMaxDamage() - 1) {
                                queue.clear();
                                break;
                            }

                            // Break with normal drops, damage tool.
                            boolean ok = serverWorld.breakBlock(next, true, serverPlayer);
                            if (ok) {
                                broken++;
                                damageTool(serverWorld, serverPlayer);
                                serverPlayer.addExhaustion(0.005f);
                            }
                        }
                    }
                }
            }
        } finally {
            GUARD.set(false);
        }
    }

    private void damageTool(ServerWorld world, ServerPlayerEntity player) {
        ItemStack stack = player.getMainHandStack();
        if (stack.isEmpty() || !stack.isDamageable()) return;
        try {
            // Yarn 1.21+: damage(amount, ServerWorld, ServerPlayerEntity, Consumer)
            stack.damage(1, world, player, item -> {
            });
        } catch (NoSuchMethodError | NoClassDefFoundError e) {
            // Fallback for older mappings: manual damage.
            try {
                stack.setDamage(stack.getDamage() + 1);
                if (stack.getDamage() >= stack.getMaxDamage()) {
                    stack.decrement(1);
                }
            } catch (Exception ignored) {
            }
        } catch (Exception ignored) {
        }
    }
}
