package com.yarg.mwpositions;

import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

/**
 * 1.21.9 overlay: use getWorld() accessors.
 */
public final class SafeLocationFinder {
    private SafeLocationFinder() {}

    public static PositionData findSafe(ServerPlayerEntity player, String targetDim, PositionData desired) {
        ServerWorld world = (ServerWorld) player.getEntityWorld();
        int cx = (int) Math.floor(desired.x);
        int cy = (int) Math.floor(desired.y);
        int cz = (int) Math.floor(desired.z);

        int maxRadius = 24;
        int minY = world.getBottomY();
        int maxY = minY + world.getDimension().logicalHeight();

        for (int r = 0; r <= maxRadius; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    int x = cx + dx;
                    int z = cz + dz;
                    int fromY = Math.max(minY, cy - 12);
                    int toY   = Math.min(maxY - 2, cy + 12);
                    for (int y = fromY; y <= toY; y++) {
                        if (isTwoTallAirWithSolidFloor(world, x, y, z)) {
                            return new PositionData(x + 0.5, y, z + 0.5, desired.yaw, desired.pitch);
                        }
                    }
                }
            }
        }
        BlockPos spawn = world.getSpawnPoint().getPos();
        return new PositionData(spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5, desired.yaw, desired.pitch);
    }

    public static boolean isTwoTallAirWithSolidFloor(ServerWorld w, int x, int y, int z) {
        BlockPos floorPos = new BlockPos(x, y - 1, z);
        BlockPos feetPos = new BlockPos(x, y, z);
        BlockPos headPos = new BlockPos(x, y + 1, z);
        boolean floorSolid = w.getBlockState(floorPos).isSolidBlock(w, floorPos);
        boolean feetEmpty = w.getBlockState(feetPos).getCollisionShape(w, feetPos).isEmpty();
        boolean headEmpty = w.getBlockState(headPos).getCollisionShape(w, headPos).isEmpty();
        boolean feetInWater = w.getFluidState(feetPos).isIn(FluidTags.WATER);
        boolean headInWater = w.getFluidState(headPos).isIn(FluidTags.WATER);
        return floorSolid && feetEmpty && headEmpty && !feetInWater && !headInWater;
    }

    public static PositionData findSafeNear(ServerPlayerEntity player, String targetDim, PositionData desired, BlockPos prefer) {
        ServerWorld world = (ServerWorld) player.getEntityWorld();
        int cx = prefer.getX();
        int cy = prefer.getY();
        int cz = prefer.getZ();
        for (int r = 0; r <= 4; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    for (int dy = -2; dy <= 2; dy++) {
                        int x = cx + dx, y = cy + dy, z = cz + dz;
                        if (isTwoTallAirWithSolidFloor(world, x, y, z)) {
                            return new PositionData(x + 0.5, y, z + 0.5, desired.yaw, desired.pitch);
                        }
                    }
                }
            }
        }
        return findSafe(player, targetDim, desired);
    }
}
