package com.yarg.mwpositions;

import net.minecraft.server.network.ServerPlayerEntity;

/**
 * Abstraction for cross-dimension teleport. Implementations can integrate with FabricDimensions
 * or server-specific teleport helpers. The default is a logging-only implementation.
 */
public interface TeleportService {
    /**
     * Attempt to teleport the player to the given target dimension key.
     *
     * @param player        the player to move
     * @param targetDimKey  string key of target dimension (e.g., "minecraft:the_nether")
     * @return true if the cross-dimension teleport was performed; false otherwise
     */
    boolean teleport(ServerPlayerEntity player, String targetDimKey);
}
