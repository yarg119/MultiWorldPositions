package com.yarg.mwpositions;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.NetherPortalBlock;
import net.minecraft.block.EndPortalFrameBlock;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction.Axis;

/**
 * Lightweight helpers for recognizing/creating portal visuals without relying fully on vanilla mechanics.
 * These are conservative heuristics good enough to trigger group portal linking UX.
 */
public final class PortalFrameUtils {
    private PortalFrameUtils() {}

    /**
     * Returns true only if a complete, rectangle-validated inner hole is detected near the click.
     */
    public static boolean isValidNetherFrame(ServerWorld world, BlockPos pos) {
        return findNetherFrameBounds(world, pos) != null;
    }

    private static boolean isAir(ServerWorld world, BlockPos pos) {
        Block b = world.getBlockState(pos).getBlock();
        return b == Blocks.AIR || b == Blocks.CAVE_AIR || b == Blocks.VOID_AIR;
    }

    private static boolean isObsidian(ServerWorld world, BlockPos pos) {
        return world.getBlockState(pos).isOf(Blocks.OBSIDIAN);
    }

    public static final class FrameBounds {
        public final Axis axis;
        public final int minX, maxX, minY, maxY, minZ, maxZ; // interior rectangle bounds
        public FrameBounds(Axis axis, int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
            this.axis = axis;
            this.minX = minX; this.maxX = maxX; this.minY = minY; this.maxY = maxY; this.minZ = minZ; this.maxZ = maxZ;
        }
        public int innerWidth() { return (axis == Axis.X) ? (maxX - minX + 1) : (maxZ - minZ + 1); }
        public int innerHeight() { return (maxY - minY + 1); }
    }

    private static boolean isInteriorEmpty(ServerWorld world, BlockPos pos) {
        BlockState st = world.getBlockState(pos);
        return st.isAir() || st.isOf(Blocks.CAVE_AIR) || st.isOf(Blocks.VOID_AIR) || st.isOf(Blocks.FIRE);
    }

    private static boolean isInteriorEmptyOrPortal(ServerWorld world, BlockPos pos) {
        BlockState st = world.getBlockState(pos);
        return isInteriorEmpty(world, pos) || st.isOf(Blocks.NETHER_PORTAL);
    }

    /**
     * Robust rectangle finder for a Nether portal frame's inner hole. Returns null if incomplete or size invalid.
     */
    public static FrameBounds findNetherFrameBounds(ServerWorld world, BlockPos near) {
        ModConfig cfg = MultiWorldPositions.getConfig();
        BlockState state = world.getBlockState(near);
        boolean nearEmpty = isInteriorEmptyOrPortal(world, near);
        Axis axisGuess = null;
        BlockPos base = near;

        if (nearEmpty) {
            if (isObsidian(world, near.north()) || isObsidian(world, near.south())) axisGuess = Axis.X;
            else if (isObsidian(world, near.east()) || isObsidian(world, near.west())) axisGuess = Axis.Z;
            // if still null, try to infer by checking farther neighbors
            if (axisGuess == null) {
                if (isObsidian(world, near.add(0, 0, -2)) || isObsidian(world, near.add(0, 0, 2))) axisGuess = Axis.X;
                else if (isObsidian(world, near.add(-2, 0, 0)) || isObsidian(world, near.add(2, 0, 0))) axisGuess = Axis.Z;
            }
        } else if (state.isOf(Blocks.OBSIDIAN)) {
            if (isInteriorEmptyOrPortal(world, near.north()) || isInteriorEmptyOrPortal(world, near.south())) { base = near.north(); axisGuess = Axis.X; }
            else if (isInteriorEmptyOrPortal(world, near.east()) || isInteriorEmptyOrPortal(world, near.west())) { base = near.east(); axisGuess = Axis.Z; }
            else {
                // Try one block up to catch bottom-edge clicks
                BlockPos up = near.up();
                if (isInteriorEmptyOrPortal(world, up.north()) || isInteriorEmptyOrPortal(world, up.south())) { base = up.north(); axisGuess = Axis.X; }
                else if (isInteriorEmptyOrPortal(world, up.east()) || isInteriorEmptyOrPortal(world, up.west())) { base = up.east(); axisGuess = Axis.Z; }
                else return null;
            }
        } else {
            return null;
        }
        if (axisGuess == null) return null;

        int baseY = base.getY();
        if (axisGuess == Axis.X) {
            int z = base.getZ();
            // Horizontal extents along X at baseY
            int minX = base.getX();
            while (isInteriorEmptyOrPortal(world, new BlockPos(minX - 1, baseY, z))) minX--;
            int maxX = base.getX();
            while (isInteriorEmptyOrPortal(world, new BlockPos(maxX + 1, baseY, z))) maxX++;
            // Vertical extents: expand while full row [minX..maxX] is interior
            int minY = baseY;
            while (rowInterior(world, minX, maxX, minY - 1, z, Axis.X)) minY--;
            int maxY = baseY;
            while (rowInterior(world, minX, maxX, maxY + 1, z, Axis.X)) maxY++;
            int width = (maxX - minX + 1);
            int height = (maxY - minY + 1);
            if (width < cfg.portalMinInnerWidth || height < cfg.portalMinInnerHeight) return null;
            if (!cfg.allowOversizeFrames) {
                if (width != cfg.portalMinInnerWidth || height != cfg.portalMinInnerHeight) return null;
            } else {
                if (width > cfg.portalMaxInnerWidth || height > cfg.portalMaxInnerHeight) return null;
            }
            // Boundaries must be obsidian
            if (!rowObsidian(world, minX, maxX, minY - 1, z, Axis.X)) return null;
            if (!rowObsidian(world, minX, maxX, maxY + 1, z, Axis.X)) return null;
            if (!colObsidian(world, minY, maxY, minX - 1, z, Axis.X)) return null;
            if (!colObsidian(world, minY, maxY, maxX + 1, z, Axis.X)) return null;
            return new FrameBounds(Axis.X, minX, maxX, minY, maxY, z, z);
        } else {
            int x = base.getX();
            int minZ = base.getZ();
            while (isInteriorEmptyOrPortal(world, new BlockPos(x, baseY, minZ - 1))) minZ--;
            int maxZ = base.getZ();
            while (isInteriorEmptyOrPortal(world, new BlockPos(x, baseY, maxZ + 1))) maxZ++;
            int minY = baseY;
            while (rowInterior(world, minZ, maxZ, minY - 1, x, Axis.Z)) minY--;
            int maxY = baseY;
            while (rowInterior(world, minZ, maxZ, maxY + 1, x, Axis.Z)) maxY++;
            int width = (maxZ - minZ + 1);
            int height = (maxY - minY + 1);
            if (width < cfg.portalMinInnerWidth || height < cfg.portalMinInnerHeight) return null;
            if (!cfg.allowOversizeFrames) {
                if (width != cfg.portalMinInnerWidth || height != cfg.portalMinInnerHeight) return null;
            } else {
                if (width > cfg.portalMaxInnerWidth || height > cfg.portalMaxInnerHeight) return null;
            }
            if (!rowObsidian(world, minZ, maxZ, minY - 1, x, Axis.Z)) return null;
            if (!rowObsidian(world, minZ, maxZ, maxY + 1, x, Axis.Z)) return null;
            if (!colObsidian(world, minY, maxY, minZ - 1, x, Axis.Z)) return null;
            if (!colObsidian(world, minY, maxY, maxZ + 1, x, Axis.Z)) return null;
            return new FrameBounds(Axis.Z, x, x, minY, maxY, minZ, maxZ);
        }
    }

    private static boolean rowInterior(ServerWorld w, int a, int b, int y, int fixed, Axis axis) {
        if (axis == Axis.X) {
            for (int x = a; x <= b; x++) if (!isInteriorEmptyOrPortal(w, new BlockPos(x, y, fixed))) return false;
        } else {
            for (int z = a; z <= b; z++) if (!isInteriorEmptyOrPortal(w, new BlockPos(fixed, y, z))) return false;
        }
        return true;
    }

    private static boolean rowObsidian(ServerWorld w, int a, int b, int y, int fixed, Axis axis) {
        if (axis == Axis.X) {
            for (int x = a; x <= b; x++) if (!w.getBlockState(new BlockPos(x, y, fixed)).isOf(Blocks.OBSIDIAN)) return false;
        } else {
            for (int z = a; z <= b; z++) if (!w.getBlockState(new BlockPos(fixed, y, z)).isOf(Blocks.OBSIDIAN)) return false;
        }
        return true;
    }

    private static boolean colObsidian(ServerWorld w, int y1, int y2, int side, int fixed, Axis axis) {
        if (axis == Axis.X) {
            for (int y = y1; y <= y2; y++) if (!w.getBlockState(new BlockPos(side, y, fixed)).isOf(Blocks.OBSIDIAN)) return false;
        } else {
            for (int y = y1; y <= y2; y++) if (!w.getBlockState(new BlockPos(fixed, y, side)).isOf(Blocks.OBSIDIAN)) return false;
        }
        return true;
    }

    /**
     * Minimal check for end portal activation: return true if the clicked block is an end portal frame.
     * Full completion detection is not required for our link trigger and keeps CPU usage low.
     */
    public static boolean wouldCompleteEndPortal(ServerWorld world, BlockPos pos, BlockState stateAtPos) {
        // Do not intercept Eye placement; let vanilla handle state updates.
        // Completion will be detected later in tick() and visualized if needed.
        return false;
    }

    /**
     * Heuristic: whether vanilla mechanics are likely to create portal blocks here.
     * We conservatively allow vanilla to handle only in default worlds.
     */
    public static boolean vanillaWillCreatePortal(ServerWorld world, BlockPos pos) {
        String dimKey = world.getRegistryKey().getValue().toString();
        return MultiWorldPositions.getConfig().isDefaultWorld(dimKey);
    }

    /**
     * Fill the entire inner opening of a rectangular obsidian frame with correctly oriented portal blocks.
     * Returns true if any blocks were placed.
     */
    public static boolean fillNetherPortalInterior(ServerWorld world, BlockPos framePos) {
        FrameBounds fb = findNetherFrameBounds(world, framePos);
        if (fb == null) return false;
        BlockState portal = Blocks.NETHER_PORTAL.getDefaultState().with(NetherPortalBlock.AXIS, fb.axis);
        boolean placed = false;
        if (fb.axis == Axis.X) {
            int z = fb.minZ; // constant plane
            for (int x = fb.minX; x <= fb.maxX; x++) {
                for (int y = fb.minY; y <= fb.maxY; y++) {
                    BlockPos p = new BlockPos(x, y, z);
                    if (isInteriorEmptyOrPortal(world, p)) {
                        world.setBlockState(p, portal);
                        placed = true;
                    }
                }
            }
        } else {
            int x = fb.minX; // constant plane
            for (int z = fb.minZ; z <= fb.maxZ; z++) {
                for (int y = fb.minY; y <= fb.maxY; y++) {
                    BlockPos p = new BlockPos(x, y, z);
                    if (isInteriorEmptyOrPortal(world, p)) {
                        world.setBlockState(p, portal);
                        placed = true;
                    }
                }
            }
        }
        if (MultiWorldPositions.getConfig().debugMode) {
            MultiWorldPositions.LOGGER.debug("[MWP] Filled portal interior: axis={}, x=[{}..{}], y=[{}..{}], z=[{}..{}] at {}",
                    fb.axis, fb.minX, fb.maxX, fb.minY, fb.maxY, fb.minZ, fb.maxZ, framePos);
        }
        return placed;
    }

    /**
     * Fill a 3x3 area with end portal blocks at approximately the frame interior height.
     */
    public static boolean fillEndPortalInterior(ServerWorld world, BlockPos centerNearFrame) {
        // Place at one block below the frame top (vanilla interior plane)
        int y = centerNearFrame.getY() - 1;
        boolean placed = false;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                BlockPos p = new BlockPos(centerNearFrame.getX() + dx, y, centerNearFrame.getZ() + dz);
                if (world.getBlockState(p).isAir()) {
                    world.setBlockState(p, Blocks.END_PORTAL.getDefaultState());
                    placed = true;
                }
            }
        }
        return placed;
    }

    /**
     * Detects whether a complete End portal ring (12 frames with eyes) surrounds a 3x3 interior
     * centered roughly around the provided position. Returns true if such a ring exists.
     */
    public static boolean isEndRingComplete(ServerWorld world, BlockPos near) {
        // We treat 'near' as approximately the center on the frame plane (y = frame top).
        // Probe a few candidate centers around 'near' to account for where the player clicked/stood.
        int frameY = world.getBlockState(near).isOf(Blocks.END_PORTAL_FRAME) ? near.getY() : near.getY();
        for (int ox = -1; ox <= 1; ox++) {
            for (int oz = -1; oz <= 1; oz++) {
                int cx = near.getX() + ox;
                int cz = near.getZ() + oz;
                if (isCompleteEndRingAt(world, cx, frameY, cz)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isCompleteEndRingAt(ServerWorld world, int cx, int frameY, int cz) {
        // Validate border: all positions where |dx|==2 or |dz|==2 within 5x5 must be END_PORTAL_FRAME with EYE=true
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (Math.abs(dx) == 2 || Math.abs(dz) == 2) {
                    BlockPos bp = new BlockPos(cx + dx, frameY, cz + dz);
                    BlockState st = world.getBlockState(bp);
                    if (!st.isOf(Blocks.END_PORTAL_FRAME)) return false;
                    Boolean eye = st.getOrEmpty(EndPortalFrameBlock.EYE).orElse(false);
                    if (eye == null || !eye) return false;
                }
            }
        }
        // Interior should be clear (air/portal) at y-1 so we can place portal blocks
        int yInterior = frameY - 1;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                BlockPos ip = new BlockPos(cx + dx, yInterior, cz + dz);
                BlockState st = world.getBlockState(ip);
                if (!(st.isAir() || st.isOf(Blocks.CAVE_AIR) || st.isOf(Blocks.VOID_AIR) || st.isOf(Blocks.END_PORTAL))) {
                    return false;
                }
            }
        }
        return true;
    }
}
