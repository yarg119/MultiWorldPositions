package com.yarg.mwpositions;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.NetherPortalBlock;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction.Axis;

/**
 * Utility to ensure a return portal exists near an intended arrival location.
 * If an existing portal is nearby, snaps to it; otherwise builds a small 4x5 frame (2x3 interior)
 * and fills the interior with correctly oriented portal blocks.
 */
public final class PortalBuilder {
    private PortalBuilder() {}

    public static BlockPos ensureReturnPortal(ServerWorld world, BlockPos near, PortalFrameUtils.FrameBounds preferred, int searchRadius) {
        ModConfig cfg = MultiWorldPositions.getConfig();
        // 1) Try to find an existing portal nearby
        BlockPos existing = findNearestPortalCell(world, near, searchRadius);
        if (existing != null) {
            if (cfg.debugMode) {
                MultiWorldPositions.LOGGER.debug("[MWP] PortalBuilder: snapped to existing portal at {}", existing);
            }
            return existing;
        }
        if (!cfg.createReturnPortal) {
            return null;
        }

        // 2) Build a minimal frame aligned by preferred axis if provided
        Axis axis = (preferred != null) ? preferred.axis : Axis.X;

        // Find a reasonable base Y: ground beneath 'near'
        int y = near.getY();
        while (y > 2 && !world.getBlockState(new BlockPos(near.getX(), y - 1, near.getZ())).isSolidBlock(world, new BlockPos(near.getX(), y - 1, near.getZ()))) {
            y--;
        }
        int baseY = Math.max(2, y);

        if (axis == Axis.X) {
            // Constant Z plane at near.getZ(); outer frame 4x5 (inner 2x3)
            int z = near.getZ();
            int leftX = near.getX() - 1;
            int rightX = leftX + 3;
            buildFrameX(world, leftX, rightX, baseY, z);
            fillInteriorX(world, leftX + 1, rightX - 1, baseY + 1, baseY + 3, z);
            // Choose inner cell closest to 'near'
            int pickX = (Math.abs(near.getX() - (leftX + 1)) <= Math.abs(near.getX() - (rightX - 1))) ? (leftX + 1) : (rightX - 1);
            return new BlockPos(pickX, baseY + 1, z);
        } else {
            // Constant X plane at near.getX()
            int x = near.getX();
            int minZ = near.getZ() - 1;
            int maxZ = minZ + 3;
            buildFrameZ(world, x, minZ, maxZ, baseY);
            fillInteriorZ(world, x, minZ + 1, maxZ - 1, baseY + 1, baseY + 3);
            int pickZ = (Math.abs(near.getZ() - (minZ + 1)) <= Math.abs(near.getZ() - (maxZ - 1))) ? (minZ + 1) : (maxZ - 1);
            return new BlockPos(x, baseY + 1, pickZ);
        }
    }

    private static BlockPos findNearestPortalCell(ServerWorld world, BlockPos near, int radius) {
        BlockPos best = null;
        double bestDist2 = Double.MAX_VALUE;
        int yMin = Math.max(near.getY() - 32, 1);
        int yMax = Math.min(near.getY() + 32, 319);
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = yMin - near.getY(); dy <= yMax - near.getY(); dy++) {
                    BlockPos p = near.add(dx, dy, dz);
                    if (world.getBlockState(p).isOf(Blocks.NETHER_PORTAL)) {
                        double d2 = p.getSquaredDistance(near.getX(), near.getY(), near.getZ());
                        if (d2 < bestDist2) {
                            bestDist2 = d2;
                            best = p.toImmutable();
                        }
                    }
                }
            }
        }
        return best;
    }

    private static void buildFrameX(ServerWorld world, int leftX, int rightX, int baseY, int z) {
        // Vertical pillars
        for (int y = baseY; y <= baseY + 4; y++) {
            world.setBlockState(new BlockPos(leftX, y, z), Blocks.OBSIDIAN.getDefaultState());
            world.setBlockState(new BlockPos(rightX, y, z), Blocks.OBSIDIAN.getDefaultState());
        }
        // Top and bottom
        for (int x = leftX; x <= rightX; x++) {
            world.setBlockState(new BlockPos(x, baseY, z), Blocks.OBSIDIAN.getDefaultState());
            world.setBlockState(new BlockPos(x, baseY + 4, z), Blocks.OBSIDIAN.getDefaultState());
        }
    }

    private static void fillInteriorX(ServerWorld world, int minX, int maxX, int minY, int maxY, int z) {
        BlockState portal = Blocks.NETHER_PORTAL.getDefaultState().with(NetherPortalBlock.AXIS, Axis.X);
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                BlockPos p = new BlockPos(x, y, z);
                if (world.getBlockState(p).isAir()) {
                    world.setBlockState(p, portal);
                }
            }
        }
    }

    private static void buildFrameZ(ServerWorld world, int x, int minZ, int maxZ, int baseY) {
        for (int y = baseY; y <= baseY + 4; y++) {
            world.setBlockState(new BlockPos(x, y, minZ), Blocks.OBSIDIAN.getDefaultState());
            world.setBlockState(new BlockPos(x, y, maxZ), Blocks.OBSIDIAN.getDefaultState());
        }
        for (int z = minZ; z <= maxZ; z++) {
            world.setBlockState(new BlockPos(x, baseY, z), Blocks.OBSIDIAN.getDefaultState());
            world.setBlockState(new BlockPos(x, baseY + 4, z), Blocks.OBSIDIAN.getDefaultState());
        }
    }

    private static void fillInteriorZ(ServerWorld world, int x, int minZ, int maxZ, int minY, int maxY) {
        BlockState portal = Blocks.NETHER_PORTAL.getDefaultState().with(NetherPortalBlock.AXIS, Axis.Z);
        for (int z = minZ; z <= maxZ; z++) {
            for (int y = minY; y <= maxY; y++) {
                BlockPos p = new BlockPos(x, y, z);
                if (world.getBlockState(p).isAir()) {
                    world.setBlockState(p, portal);
                }
            }
        }
    }
}
