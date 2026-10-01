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
import net.minecraft.world.phys.Vec3;
import org.veinmine.vienmine.network.VeinmineActivePayload;
import org.veinmine.vienmine.network.VeinmineShapePayload;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class Vienmine implements ModInitializer {

    public static final String MODID = "vienmine";

    // Max extra blocks per operation (anti-lag / anti-abuse). Change as you like.
    public static final int MAX_BLOCKS = 64;
    // BFS visit cap so shapeless never scans the whole map.
    private static final int MAX_SEARCH = 512;
    // Max radius from the first broken block (shapeless).
    private static final int RADIUS = 8;
    // Forward depth for tunnel / vertical length for column.
    private static final int TUNNEL_DEPTH = 16;
    // Min time between two veinmine operations (ms).
    private static final long COOLDOWN_MS = 250L;

    // UUIDs of players currently HOLDING the veinmine key (synced from client).
    private static final Set<UUID> ACTIVE = ConcurrentHashMap.newKeySet();
    // Selected shape per player (synced from client, defaults to SHAPELESS).
    private static final Map<UUID, Shape> SHAPES = new ConcurrentHashMap<>();
    // Last operation timestamp per player (cooldown).
    private static final Map<UUID, Long> LAST_USE = new ConcurrentHashMap<>();

    // Guard against recursion: destroyBlock inside AFTER fires AFTER again.
    private static final ThreadLocal<Boolean> GUARD = ThreadLocal.withInitial(() -> false);

    @Override
    public void onInitialize() {
        // Register client->server payloads.
        PayloadTypeRegistry.serverboundPlay().register(VeinmineActivePayload.ID, VeinmineActivePayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(VeinmineShapePayload.ID, VeinmineShapePayload.CODEC);

        ServerPlayNetworking.registerGlobalReceiver(VeinmineActivePayload.ID, (payload, context) -> {
            UUID id = context.player().getUUID();
            if (payload.active()) {
                ACTIVE.add(id);
            } else {
                ACTIVE.remove(id);
            }
        });

        ServerPlayNetworking.registerGlobalReceiver(VeinmineShapePayload.ID, (payload, context) ->
                SHAPES.put(context.player().getUUID(), Shape.byOrdinal(payload.shape())));

        // Cleanup on disconnect.
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            UUID id = handler.getPlayer().getUUID();
            ACTIVE.remove(id);
            SHAPES.remove(id);
            LAST_USE.remove(id);
        });

        PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, blockEntity) ->
                onBlockBroken(world, player, pos, state));
    }

    private void onBlockBroken(Level world, Player player, BlockPos originPos, BlockState originState) {
        if (world.isClientSide()) return;
        if (GUARD.get()) return;
        if (player.isCreative() || player.isSpectator()) return;
        if (!ACTIVE.contains(player.getUUID())) return; // key NOT held -> normal mine
        if (!(player instanceof ServerPlayer serverPlayer)) return;

        long now = System.currentTimeMillis();
        Long last = LAST_USE.get(player.getUUID());
        if (last != null && now - last < COOLDOWN_MS) return; // on cooldown -> normal mine
        LAST_USE.put(player.getUUID(), now);

        Shape shape = SHAPES.getOrDefault(player.getUUID(), Shape.SHAPELESS);
        List<BlockPos> targets = collectTargets(world, originPos, originState.getBlock(), shape,
                player.getViewVector(1.0f));

        if (targets.isEmpty()) return;

        GUARD.set(true);
        try {
            int broken = 0;
            for (BlockPos next : targets) {
                if (broken >= MAX_BLOCKS) break;
                if (player.getMainHandItem().isEmpty()) break; // tool broke -> stop

                BlockState targetState;
                try {
                    targetState = world.getBlockState(next);
                } catch (Exception e) {
                    continue;
                }
                if (targetState.isAir()) continue;

                // Full vanilla break: drops (Fortune/Silk Touch), tool damage, stats.
                boolean ok = serverPlayer.gameMode.destroyBlock(next);
                if (ok) {
                    broken++;
                    player.causeFoodExhaustion(0.005f);
                }
            }
        } finally {
            GUARD.set(false);
        }
    }

    private List<BlockPos> collectTargets(Level world, BlockPos origin, Block originBlock, Shape shape, Vec3 look) {
        return switch (shape) {
            case TUNNEL -> collectTunnel(origin, look);
            case SQUARE -> collectSquare(origin, look);
            case COLUMN -> collectColumn(origin, look);
            default -> collectVein(world, origin, originBlock);
        };
    }

    /** FTB-style shapeless: flood-fill blocks of the SAME type. */
    private List<BlockPos> collectVein(Level world, BlockPos origin, Block originBlock) {
        List<BlockPos> out = new ArrayList<>();
        Set<BlockPos> visited = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        queue.add(origin.immutable());
        visited.add(origin.immutable());
        int searched = 0;

        while (!queue.isEmpty() && out.size() < MAX_BLOCKS && searched < MAX_SEARCH) {
            BlockPos current = queue.poll();
            searched++;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;
                        BlockPos next = current.offset(dx, dy, dz);
                        double ddx = next.getX() - origin.getX();
                        double ddy = next.getY() - origin.getY();
                        double ddz = next.getZ() - origin.getZ();
                        if (ddx * ddx + ddy * ddy + ddz * ddz > (double) RADIUS * RADIUS) continue;
                        if (!visited.add(next.immutable())) continue;
                        if (searched++ > MAX_SEARCH) break;

                        BlockState s;
                        try {
                            s = world.getBlockState(next);
                        } catch (Exception e) {
                            continue;
                        }
                        if (s.isAir() || !s.is(originBlock)) continue;
                        queue.add(next.immutable());
                        if (!next.equals(origin)) {
                            out.add(next.immutable());
                            if (out.size() >= MAX_BLOCKS) return out;
                        }
                    }
                }
            }
        }
        return out;
    }

    /** Dominant look axis: 0 = X, 1 = Y, 2 = Z. */
    private int dominantAxis(Vec3 look) {
        double ax = Math.abs(look.x), ay = Math.abs(look.y), az = Math.abs(look.z);
        if (ay >= ax && ay >= az) return 1;
        if (ax >= az) return 0;
        return 2;
    }

    /** 3x3 tunnel going forward along the look direction (any block, like FTB shaped mode). */
    private List<BlockPos> collectTunnel(BlockPos origin, Vec3 look) {
        List<BlockPos> out = new ArrayList<>();
        int axis = dominantAxis(look);
        int sx = axis == 0 ? (look.x > 0 ? 1 : -1) : 0;
        int sy = axis == 1 ? (look.y > 0 ? 1 : -1) : 0;
        int sz = axis == 2 ? (look.z > 0 ? 1 : -1) : 0;

        for (int d = 0; d < TUNNEL_DEPTH && out.size() < MAX_BLOCKS; d++) {
            for (int a = -1; a <= 1; a++) {
                for (int b = -1; b <= 1; b++) {
                    // 3x3 cross-section perpendicular to the tunnel axis.
                    int x = axis == 0 ? origin.getX() + sx * d : origin.getX() + a;
                    int y = axis == 1 ? origin.getY() + sy * d : origin.getY() + (axis == 0 ? a : b);
                    int z = axis == 2 ? origin.getZ() + sz * d : origin.getZ() + b;
                    BlockPos p = new BlockPos(x, y, z);
                    if (p.equals(origin)) continue;
                    out.add(p);
                    if (out.size() >= MAX_BLOCKS) return out;
                }
            }
        }
        return out;
    }

    /** Flat 3x3 panel perpendicular to the look direction. */
    private List<BlockPos> collectSquare(BlockPos origin, Vec3 look) {
        List<BlockPos> out = new ArrayList<>();
        int axis = dominantAxis(look);
        for (int a = -1; a <= 1; a++) {
            for (int b = -1; b <= 1; b++) {
                int x = axis == 0 ? origin.getX() : origin.getX() + a;
                int y = axis == 1 ? origin.getY() : origin.getY() + (axis == 0 ? a : b);
                int z = axis == 2 ? origin.getZ() : origin.getZ() + b;
                BlockPos p = new BlockPos(x, y, z);
                if (p.equals(origin)) continue;
                out.add(p);
            }
        }
        return out;
    }

    /** 1x1 vertical column: upward when looking up (escape), else downward (shaft). */
    private List<BlockPos> collectColumn(BlockPos origin, Vec3 look) {
        List<BlockPos> out = new ArrayList<>();
        int sy = look.y > -0.2 ? 1 : -1;
        for (int d = 0; d < TUNNEL_DEPTH && out.size() < MAX_BLOCKS; d++) {
            BlockPos p = origin.offset(0, sy * d, 0);
            if (p.equals(origin)) continue;
            out.add(p);
        }
        return out;
    }
}
