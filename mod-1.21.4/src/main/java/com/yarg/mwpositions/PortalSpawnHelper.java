package com.yarg.mwpositions;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.NetherPortalBlock;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction.Axis;

/**
 * Helper to spawn a standard 4x5 Nether portal frame (2x3 interior) at a nearby open spot,
 * oriented by the player's yaw preference. Returns basic FrameBounds for axis hinting.
 * This does not teleport; it only builds a valid, lit portal.
 */
public final class PortalSpawnHelper {
    private PortalSpawnHelper() {}

    public static final class SpawnResult {
        public final boolean success;
        public final PortalFrameUtils.FrameBounds frameBounds; // may be null if not computed
        public SpawnResult(boolean success, PortalFrameUtils.FrameBounds fb) { this.success = success; this.frameBounds = fb; }
        public static SpawnResult fail() { return new SpawnResult(false, null); }
    }

    public static SpawnResult findSpotAndBuild(ServerWorld world, BlockPos near, float yawDegrees) {
        ModConfig cfg = MultiWorldPositions.getConfig();
        int r = Math.max(2, cfg.specialPortalClearanceRadius);
        // Try several candidate bases around the clicked position (small neighborhood)
        for (int dy = -1; dy <= 2; dy++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    BlockPos base = near.add(dx, dy, dz);
                    Axis axisPref = yawToAxis(yawDegrees);
                    PortalFrameUtils.FrameBounds built = tryBuildAt(world, base, axisPref);
                    if (built != null) return new SpawnResult(true, built);
                    Axis other = (axisPref == Axis.X) ? Axis.Z : Axis.X;
                    built = tryBuildAt(world, base, other);
                    if (built != null) return new SpawnResult(true, built);
                }
            }
        }
        return SpawnResult.fail();
    }

    public static Axis yawToAxis(float yaw) {
        float a = ((yaw % 360f) + 360f) % 360f;
        // Facing north/south (~0/180) => portal plane along Z => Axis.Z; east/west (~90/270) => Axis.X
        return (a > 45 && a < 135) || (a > 225 && a < 315) ? Axis.X : Axis.Z;
    }

    public static SpawnResult buildAtCorner(ServerWorld world, BlockPos corner, Axis axis) {
        int baseY = Math.max(2, corner.getY());
        if (axis == Axis.X) {
            int leftX = corner.getX();
            int rightX = leftX + 3;
            int z = corner.getZ();
            if (!clearRect(world, leftX + 1, rightX - 1, baseY + 1, baseY + 3, z, Axis.X)) return SpawnResult.fail();
            buildFrameX(world, leftX, rightX, baseY, z);
            fillInteriorX(world, leftX + 1, rightX - 1, baseY + 1, baseY + 3, z);
            return new SpawnResult(true, new PortalFrameUtils.FrameBounds(Axis.X, leftX + 1, rightX - 1, baseY + 1, baseY + 3, z, z));
        } else {
            int x = corner.getX();
            int minZ = corner.getZ();
            int maxZ = minZ + 3;
            if (!clearRect(world, minZ + 1, maxZ - 1, baseY + 1, baseY + 3, x, Axis.Z)) return SpawnResult.fail();
            buildFrameZ(world, x, minZ, maxZ, baseY);
            fillInteriorZ(world, x, minZ + 1, maxZ - 1, baseY + 1, baseY + 3);
            return new SpawnResult(true, new PortalFrameUtils.FrameBounds(Axis.Z, x, x, baseY + 1, baseY + 3, minZ + 1, maxZ - 1));
        }
    }

    private static PortalFrameUtils.FrameBounds tryBuildAt(ServerWorld world, BlockPos base, Axis axis) {
        // Build standard outer 4x5 with inner 2x3. Ensure ground under bottom.
        int y = base.getY();
        // Find ground one block below bottom edge (conservative)
        while (y > 2 && !world.getBlockState(new BlockPos(base.getX(), y - 1, base.getZ())).isSolidBlock(world, new BlockPos(base.getX(), y - 1, base.getZ()))) {
            y--;
        }
        int baseY = Math.max(2, y);
        if (axis == Axis.X) {
            int z = base.getZ();
            int leftX = base.getX() - 1;
            int rightX = leftX + 3;
            if (!clearRect(world, leftX, rightX, baseY + 1, baseY + 3, z, axis)) return null;
            // Build and fill
            buildFrameX(world, leftX, rightX, baseY, z);
            fillInteriorX(world, leftX + 1, rightX - 1, baseY + 1, baseY + 3, z);
            return new PortalFrameUtils.FrameBounds(Axis.X, leftX + 1, rightX - 1, baseY + 1, baseY + 3, z, z);
        } else {
            int x = base.getX();
            int minZ = base.getZ() - 1;
            int maxZ = minZ + 3;
            if (!clearRect(world, minZ, maxZ, baseY + 1, baseY + 3, x, axis)) return null;
            buildFrameZ(world, x, minZ, maxZ, baseY);
            fillInteriorZ(world, x, minZ + 1, maxZ - 1, baseY + 1, baseY + 3);
            return new PortalFrameUtils.FrameBounds(Axis.Z, x, x, baseY + 1, baseY + 3, minZ + 1, maxZ - 1);
        }
    }

    private static boolean clearRect(ServerWorld w, int a, int b, int minY, int maxY, int fixed, Axis axis) {
        for (int y = minY; y <= maxY; y++) {
            if (axis == Axis.X) {
                for (int x = a; x <= b; x++) {
                    BlockPos p = new BlockPos(x, y, fixed);
                    if (!w.getBlockState(p).isAir()) return false;
                }
            } else {
                for (int z = a; z <= b; z++) {
                    BlockPos p = new BlockPos(fixed, y, z);
                    if (!w.getBlockState(p).isAir()) return false;
                }
            }
        }
        return true;
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
                BlockState st = world.getBlockState(p);
                if (st.isAir() || st.isOf(Blocks.FIRE)) {
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
                BlockState st = world.getBlockState(p);
                if (st.isAir() || st.isOf(Blocks.FIRE)) {
                    world.setBlockState(p, portal);
                }
            }
        }
    }
}
