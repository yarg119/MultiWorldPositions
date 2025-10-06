package com.yarg.mwpositions;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

/**
 * Utilities for safe arrival into a group's End world. Builds a small platform near the main island
 * and optionally spawns the Ender Dragon.
 */
public final class EndArrivalHelper {
    private EndArrivalHelper() {}

    public static BlockPos ensureArrivalPlatform(ServerWorld endWorld, BlockPos near) {
        ModConfig cfg = MultiWorldPositions.getConfig();
        if (!cfg.endCreateArrivalPlatform) {
            return near;
        }
        // Prefer near main island: search around (0, 0) for top-most end stone
        BlockPos center = new BlockPos(0, near.getY(), 0);
        BlockPos place = findTopOfEndStone(endWorld, center, cfg.endMainIslandSearchRadius);
        if (place == null) {
            // Fallback: use the desired location’s vertical vicinity
            int y = Math.max(50, Math.min(near.getY(), 80));
            place = new BlockPos(near.getX(), y, near.getZ());
        }
        Block platformBlock = Blocks.END_STONE;
        try {
            // Mapping-agnostic: keep END_STONE; endPlatformBlock left for future registry lookup
            Identifier id = Identifier.of(cfg.endPlatformBlock);
            platformBlock = Blocks.END_STONE;
        } catch (Throwable ignored) {}
        buildSquare(endWorld, place.down(), cfg.endPlatformRadius, platformBlock.getDefaultState());
        if (MultiWorldPositions.getConfig().debugMode) {
            MultiWorldPositions.LOGGER.info("[MWP] End arrival platform at {} (r={})", place, cfg.endPlatformRadius);
        }
        return place.up();
    }

    private static void buildSquare(ServerWorld w, BlockPos center, int r, BlockState state) {
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                w.setBlockState(center.add(dx, 0, dz), state);
            }
        }
    }

    private static BlockPos findTopOfEndStone(ServerWorld w, BlockPos around, int radius) {
        int yMax = 120; // above main island top
        int yMin = 20;  // below main island base
        BlockPos best = null;
        double bestD2 = Double.MAX_VALUE;
        for (int dx = -radius; dx <= radius; dx += 4) {
            for (int dz = -radius; dz <= radius; dz += 4) {
                int x = around.getX() + dx;
                int z = around.getZ() + dz;
                for (int y = yMax; y >= yMin; y--) {
                    BlockPos p = new BlockPos(x, y, z);
                    if (w.getBlockState(p).isOf(Blocks.END_STONE)) {
                        double d2 = p.getSquaredDistance(0.5, y + 0.5, 0.5);
                        if (d2 < bestD2) { bestD2 = d2; best = p.toImmutable(); }
                        break;
                    }
                }
            }
        }
        return best;
    }

    public static void spawnDragonIfEnabled(ServerWorld endWorld) {
        ModConfig cfg = MultiWorldPositions.getConfig();
        if (!cfg.endSpawnDragonOnArrival) return;
        try {
            // Summon via command at origin area. This is simple and avoids mapping-specific APIs.
            String cmd = "execute in " + endWorld.getRegistryKey().getValue() + " run summon minecraft:ender_dragon 0 80 0";
            endWorld.getServer().getCommandManager().executeWithPrefix(endWorld.getServer().getCommandSource().withLevel(4), cmd);
        } catch (Exception e) {
            MultiWorldPositions.LOGGER.warn("[MWP] Failed to spawn Ender Dragon by command", e);
        }
    }
}
