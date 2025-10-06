package com.yarg.mwpositions;

import net.minecraft.server.network.ServerPlayerEntity;

/**
 * Event-driven portal link handlers invoked from UseBlockCallback.
 */
public final class PortalLinkHandlers {
    private PortalLinkHandlers() {}

    public static boolean teleportGroupNether(ServerPlayerEntity player, WorldGroup g, String fromDim) {
        String targetDim = MultiWorldPositions.getConfig().nextForPortal(g, fromDim, PortalKind.NETHER);
        if (targetDim == null) return false;
        PositionData targetPos = computeNetherLinkedCoords(player, g, fromDim);
        boolean ok = MultiWorldPositions.getTeleportService().teleport(player, targetDim);
        if (ok) {
            PositionData adjusted = clampYIfNeeded(player, targetPos);
            player.networkHandler.requestTeleport(adjusted.x, adjusted.y, adjusted.z, player.getYaw(), player.getPitch());
            return true;
        }
        return false;
    }

    public static boolean teleportGroupEnd(ServerPlayerEntity player, WorldGroup g, String fromDim) {
        String targetDim = MultiWorldPositions.getConfig().nextForPortal(g, fromDim, PortalKind.END);
        if (targetDim == null) return false;
        PositionData targetPos = computeEndLinkedCoords(player, g, fromDim);
        boolean ok = MultiWorldPositions.getTeleportService().teleport(player, targetDim);
        if (ok) {
            PositionData adjusted = clampYIfNeeded(player, targetPos);
            player.networkHandler.requestTeleport(adjusted.x, adjusted.y, adjusted.z, player.getYaw(), player.getPitch());
            return true;
        }
        return false;
    }

    private static PositionData computeNetherLinkedCoords(ServerPlayerEntity p, WorldGroup g, String fromDim) {
        double x = p.getX();
        double y = p.getY();
        double z = p.getZ();
        double scale = MultiWorldPositions.getConfig().isGroupOverworld(g, fromDim)
                ? g.netherScaleOverworldToNether
                : g.netherScaleNetherToOverworld;
        return new PositionData(x * scale, y, z * scale, p.getYaw(), p.getPitch());
    }

    private static PositionData computeEndLinkedCoords(ServerPlayerEntity p, WorldGroup g, String fromDim) {
        return new PositionData(p.getX(), p.getY(), p.getZ(), p.getYaw(), p.getPitch());
    }

    private static PositionData clampYIfNeeded(ServerPlayerEntity player, PositionData in) {
        ModConfig cfg = MultiWorldPositions.getConfig();
        if (!cfg.clampYToWorldBounds) return in;
        double y = in.y;
        if (y < 0) y = 0;
        if (y > 320) y = 320;
        return new PositionData(in.x, y, in.z, in.yaw, in.pitch);
    }
}
