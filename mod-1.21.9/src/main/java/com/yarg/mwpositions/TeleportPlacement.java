package com.yarg.mwpositions;

import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.BlockPos;

/**
 * 1.21.9 overlay: use getWorld() accessors and keep downward snap.
 */
public final class TeleportPlacement {
    private TeleportPlacement() {}

    public static PositionData placePlayerSafely(ServerPlayerEntity player, String targetDim, PositionData desired) {
        PositionData safe = SafeLocationFinder.findSafe(player, targetDim, desired);

        ModConfig cfg = MultiWorldPositions.getConfig();
        if (cfg.killBlockingMobs) {
            ServerWorld w = (ServerWorld) player.getEntityWorld();
            double r = Math.max(0.25, cfg.killBlockingRadius);
            Box box = new Box(safe.x - r, safe.y, safe.z - r, safe.x + r, safe.y + 2.0, safe.z + r);
            for (Entity e : w.getOtherEntities(player, box)) {
                if (e instanceof LivingEntity le && !(e instanceof ServerPlayerEntity)) {
                    try { le.kill((ServerWorld) player.getEntityWorld()); } catch (Throwable ignored) {}
                    if (!e.isRemoved()) e.discard();
                }
            }
        }

        calm(player);
        player.networkHandler.requestTeleport(safe.x, safe.y, safe.z, safe.yaw, safe.pitch);
        return safe;
    }

    public static PositionData placeExactlyOrNearby(ServerPlayerEntity player, String targetDim, PositionData desired) {
        ServerWorld w = (ServerWorld) player.getEntityWorld();
        int ex = (int) Math.floor(desired.x);
        int ey = (int) Math.floor(desired.y);
        int ez = (int) Math.floor(desired.z);

        // First, try the EXACT saved position (including fractional Y)
        // This is the most accurate - if the player was standing on a block, they should return to that exact spot
        BlockPos feetPos = new BlockPos(ex, ey, ez);
        BlockPos headPos = new BlockPos(ex, ey + 1, ez);

        // Check if the exact saved location is safe (air at feet and head level)
        if (w.getBlockState(feetPos).getCollisionShape(w, feetPos).isEmpty() &&
            w.getBlockState(headPos).getCollisionShape(w, headPos).isEmpty()) {
            // Check if there's a solid floor below (at y-1)
            BlockPos floorPos = new BlockPos(ex, ey - 1, ez);
            if (w.getBlockState(floorPos).isSolidBlock(w, floorPos) || ey == w.getBottomY()) {
                // Safe to teleport to exact position
                calm(player);
                player.networkHandler.requestTeleport(desired.x, desired.y, desired.z, desired.yaw, desired.pitch);
                return desired;
            }
        }

        // If exact position isn't safe, search downward from the saved Y level
        int minY = w.getBottomY();
        for (int y = ey; y >= Math.max(minY, ey - 48); y--) {
            if (SafeLocationFinder.isTwoTallAirWithSolidFloor(w, ex, y, ez)) {
                PositionData p = new PositionData(ex + 0.5, y, ez + 0.5, desired.yaw, desired.pitch);
                calm(player);
                player.networkHandler.requestTeleport(p.x, p.y, p.z, p.yaw, p.pitch);
                return p;
            }
        }

        // Last resort: search nearby area
        PositionData near = SafeLocationFinder.findSafeNear(player, targetDim, desired, new BlockPos(ex, ey, ez));
        calm(player);
        player.networkHandler.requestTeleport(near.x, near.y, near.z, near.yaw, near.pitch);
        return near;
    }

    private static void calm(ServerPlayerEntity player) {
        player.setVelocity(0, 0, 0);
        player.velocityModified = true;
        player.fallDistance = 0f;
    }
}
