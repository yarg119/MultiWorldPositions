package com.yarg.mwpositions;

import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.BlockPos;

/**
 * Utility to place the player safely at a target location, clearing any blocking mobs
 * in a tight area and using SafeLocationFinder to avoid suffocation.
 */
public final class TeleportPlacement {
    private TeleportPlacement() {}

    public static PositionData placePlayerSafely(ServerPlayerEntity player, String targetDim, PositionData desired) {
        // After a successful cross-dimension teleport, the player's world is already the target world
        PositionData safe = SafeLocationFinder.findSafe(player, targetDim, desired);

        // Optional: clear blocking mobs/entities in a small box where we will place the player
        ModConfig cfg = MultiWorldPositions.getConfig();
        if (cfg.killBlockingMobs) {
            ServerWorld w = player.getServerWorld();
            double r = Math.max(0.25, cfg.killBlockingRadius);
            Box box = new Box(safe.x - r, safe.y, safe.z - r, safe.x + r, safe.y + 2.0, safe.z + r);
            for (Entity e : w.getOtherEntities(player, box)) {
                if (e instanceof LivingEntity le && !(e instanceof ServerPlayerEntity)) {
                    try { le.kill(player.getServerWorld()); } catch (Throwable ignored) {}
                    if (!e.isRemoved()) e.discard();
                }
            }
        }

        // Calm motion before client position set to reduce movement warnings
        calm(player);
        // Finally, place the player
        player.networkHandler.requestTeleport(safe.x, safe.y, safe.z, safe.yaw, safe.pitch);
        return safe;
    }

    /**
     * Try to place the player at the exact desired position if it's immediately safe; otherwise
     * perform a very small local search around the desired coordinates (no large drift), then place.
     */
    public static PositionData placeExactlyOrNearby(ServerPlayerEntity player, String targetDim, PositionData desired) {
        ServerWorld w = player.getServerWorld();
        int ex = (int) Math.floor(desired.x);
        int ey = (int) Math.floor(desired.y);
        int ez = (int) Math.floor(desired.z);

        // 0) Exact cell
        if (SafeLocationFinder.isTwoTallAirWithSolidFloor(w, ex, ey, ez)) {
            calm(player);
            player.networkHandler.requestTeleport(desired.x, desired.y, desired.z, desired.yaw, desired.pitch);
            return desired;
        }

        // 1) Snap straight down up to 48 blocks to keep X/Z and find the nearest safe ground
        int minY = w.getBottomY();
        for (int y = ey; y >= Math.max(minY, ey - 48); y--) {
            if (SafeLocationFinder.isTwoTallAirWithSolidFloor(w, ex, y, ez)) {
                PositionData p = new PositionData(ex + 0.5, y, ez + 0.5, desired.yaw, desired.pitch);
                calm(player);
                player.networkHandler.requestTeleport(p.x, p.y, p.z, p.yaw, p.pitch);
                return p;
            }
        }

        // 2) Small local search around the target
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
