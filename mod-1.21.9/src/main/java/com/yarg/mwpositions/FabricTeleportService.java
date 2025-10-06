package com.yarg.mwpositions;

import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;

/**
 * 1.21.9 overlay: Same behavior, but debug reads world via getServerWorld().
 */
public class FabricTeleportService implements TeleportService {
    @Override
    public boolean teleport(ServerPlayerEntity player, String targetDimKey) {
        try {
            Identifier id = Identifier.of(targetDimKey);
            RegistryKey<World> key = RegistryKey.of(RegistryKeys.WORLD, id);
            ServerWorld target = player.getCommandSource().getServer().getWorld(key);
            if (target == null) {
                MultiWorldPositions.LOGGER.warn("[MWP] Target world not found: {}", targetDimKey);
                return false;
            }

            player.setVelocity(0, 0, 0);
            player.velocityModified = true;
            player.fallDistance = 0f;

            net.minecraft.server.command.ServerCommandSource src = player.getCommandSource().withSilent();
            String cmd = "execute in " + targetDimKey + " run tp @s ~ ~ ~";
            player.getCommandSource().getServer().getCommandManager().executeWithPrefix(src, cmd);

            if (MultiWorldPositions.getConfig().debugMode) {
                String afterKey = player.getEntityWorld().getRegistryKey().getValue().toString();
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
