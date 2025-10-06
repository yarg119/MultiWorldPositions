package com.yarg.mwpositions;

import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;

/**
 * TeleportService implementation that performs actual cross-dimension moves
 * by issuing a server command to change the execution dimension, then tp @s.
 * After this cross-world hop, the listener sets the exact saved coordinates.
 */
public class FabricTeleportService implements TeleportService {
    @Override
    public boolean teleport(ServerPlayerEntity player, String targetDimKey) {
        try {
            // Validate the target world exists first
            Identifier id = Identifier.of(targetDimKey);
            RegistryKey<World> key = RegistryKey.of(RegistryKeys.WORLD, id);
            ServerWorld target = player.getServer().getWorld(key);
            if (target == null) {
                MultiWorldPositions.LOGGER.warn("[MWP] Target world not found: {}", targetDimKey);
                return false;
            }

            // Zero velocity to avoid anti-cheat flags and carryover motion
            player.setVelocity(0, 0, 0);
            player.velocityModified = true;
            player.fallDistance = 0f;

            // Cross-dimension hop via command dispatcher; targets only this player (@s)
            net.minecraft.server.command.ServerCommandSource src = player.getCommandSource().withSilent();
            String cmd = "execute in " + targetDimKey + " run tp @s ~ ~ ~";
            player.getServer().getCommandManager().executeWithPrefix(src, cmd);

            if (MultiWorldPositions.getConfig().debugMode) {
                String afterKey = player.getWorld().getRegistryKey().getValue().toString();
                MultiWorldPositions.LOGGER.debug("[MWP] TeleportService: post-teleport world for {} is {}",
                        player.getName().getString(), afterKey);
            }
            return true;
        } catch (Exception e) {
            MultiWorldPositions.LOGGER.warn("[MWP] Failed to cross-dimension teleport to {}", targetDimKey, e);
            return false;
        }
    }
}
