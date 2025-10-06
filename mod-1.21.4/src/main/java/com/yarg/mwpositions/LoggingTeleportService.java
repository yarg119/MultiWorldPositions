package com.yarg.mwpositions;

import net.minecraft.server.network.ServerPlayerEntity;

/**
 * Default implementation that only logs the intent to teleport. Useful for environments
 * where a real cross-dimension implementation is not linked.
 */
public class LoggingTeleportService implements TeleportService {
    @Override
    public boolean teleport(ServerPlayerEntity player, String targetDimKey) {
        MultiWorldPositions.LOGGER.info("[MWP] (LoggingTeleportService) Intended cross-dimension move: {} -> {}",
                player.getName().getString(), targetDimKey);
        // Return false to indicate nothing actually happened, so callers may choose a fallback.
        return false;
    }
}
