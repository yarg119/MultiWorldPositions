package com.yarg.mwpositions;

import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class DimensionChangeListener {

    // Debounce to avoid rapid re-entrancy loops
    private static final long REDIRECT_DEBOUNCE_MS = 400L;
    private static final Map<UUID, Long> lastRedirectAt = new ConcurrentHashMap<>();

    public static void register() {
        // AFTER_RESPAWN fires for both death respawns (alive=false) and some cross-dimension paths (alive=true)
        // Save the origin position here, then reuse common logic for the destination.
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
            PositionStorage storage = MultiWorldPositions.getPositionStorage();
            ModConfig config = MultiWorldPositions.getConfig();
            String oldDim = oldPlayer.getWorld().getRegistryKey().getValue().toString();
            String newDim = newPlayer.getWorld().getRegistryKey().getValue().toString();

            // Honor vanilla End exit: skip restore/redirect and keep vanilla bed/anchor placement
            boolean endExit = "minecraft:the_end".equals(oldDim) && "minecraft:overworld".equals(newDim);
            if (endExit) {
                if (config.debugMode) {
                    MultiWorldPositions.LOGGER.debug("[MWP] End exit respawn detected for {}. Skipping restore/redirect.",
                            newPlayer.getGameProfile().getName());
                }
                // Optionally persist the vanilla-placed spawn as the current saved position
                if (!config.isHubWorld(newDim)) {
                    PositionData here = new PositionData(newPlayer.getX(), newPlayer.getY(), newPlayer.getZ(), newPlayer.getYaw(), newPlayer.getPitch());
                    storage.setPosition(newPlayer.getUuid(), newDim, here);
                }
                storage.savePlayerData(newPlayer.getUuid());
                return; // IMPORTANT: do not fall through
            }

            // NEW: If this was a death respawn, let vanilla bed/anchor/world-spawn logic stand.
            if (!alive) {
                if (config.debugMode) {
                    MultiWorldPositions.LOGGER.debug("[MWP] Death respawn detected for {}: {} -> {}. Skipping restore/redirect and honoring vanilla bed/anchor.",
                            newPlayer.getGameProfile().getName(), oldDim, newDim);
                }
                // Optionally persist the vanilla-placed spawn as the current saved position for this dimension
                if (!config.isHubWorld(newDim)) {
                    PositionData here = new PositionData(newPlayer.getX(), newPlayer.getY(), newPlayer.getZ(), newPlayer.getYaw(), newPlayer.getPitch());
                    storage.setPosition(newPlayer.getUuid(), newDim, here);
                }
                storage.savePlayerData(newPlayer.getUuid());
                return; // IMPORTANT: do not fall through to the normal restore/redirect logic
            }

            // Non-death respawn (rare) or other flows that reuse AFTER_RESPAWN: previous behavior
            if (!config.isHubWorld(oldDim)) {
                storage.savePosition(oldPlayer);
                if (config.debugMode) {
                    MultiWorldPositions.LOGGER.debug("Saved position in old dimension: {}", oldDim);
                }
            } else if (config.debugMode) {
                MultiWorldPositions.LOGGER.debug("Skipped saving position (hub world): {}", oldDim);
            }

            handleWithKeys(newPlayer, oldDim, newDim);
        });
    }

    private static void handleDimensionChange(ServerPlayerEntity oldPlayer, ServerPlayerEntity newPlayer) {
        PositionStorage storage = MultiWorldPositions.getPositionStorage();
        ModConfig config = MultiWorldPositions.getConfig();

        String oldDimension = oldPlayer.getWorld().getRegistryKey().getValue().toString();
        String newDimension = newPlayer.getWorld().getRegistryKey().getValue().toString();

        // Save position from old dimension (unless it's a hub world)
        if (!config.isHubWorld(oldDimension)) {
            storage.savePosition(oldPlayer);

            if (config.debugMode) {
                MultiWorldPositions.LOGGER.debug("Saved position in old dimension: {}", oldDimension);
            }
        } else if (config.debugMode) {
            MultiWorldPositions.LOGGER.debug("Skipped saving position (hub world): {}", oldDimension);
        }

        // Default-world redirect logic (config-gated)
        boolean newIsDefault = config.isDefaultWorld(newDimension);
        boolean cameFromNonDefault = !config.isDefaultWorld(oldDimension);
        if (newIsDefault && cameFromNonDefault && config.enableCrossDimRedirect) {
            String lastDefault = storage.getLastDefaultDimension(newPlayer.getUuid());
            if (lastDefault != null && !lastDefault.equals(newDimension)) {
                PositionData saved = storage.getPosition(newPlayer.getUuid(), lastDefault);
                if (saved != null) {
                    if (debounced(newPlayer.getUuid())) {
                        if (config.debugMode) {
                            MultiWorldPositions.LOGGER.debug("Debounced redirect for {}", newPlayer.getName().getString());
                        }
                    } else {
                        boolean success = crossDimTeleport(newPlayer, lastDefault, saved);
                        if (success) {
                            lastRedirectAt.put(newPlayer.getUuid(), System.currentTimeMillis());
                            MultiWorldPositions.LOGGER.info("Redirected {} to last default dimension {} and restored position",
                                    newPlayer.getName().getString(), lastDefault);
                            storage.savePlayerData(newPlayer.getUuid());
                            return;
                        } else if (config.failOpenOnTeleportError) {
                            MultiWorldPositions.LOGGER.warn("Cross-dimension redirect failed or not available; falling back to in-dimension restore for {}",
                                    newPlayer.getName().getString());
                        }
                    }
                } else if (config.debugMode) {
                    MultiWorldPositions.LOGGER.debug("No saved position for last default {}. Skipping redirect.", lastDefault);
                }
            }
            // fallthrough to in-dimension restore
        }

        // Try to restore position in new dimension (unless it's a hub world)
        if (!config.isHubWorld(newDimension)) {
            PositionData savedPos = storage.getPosition(newPlayer.getUuid(), newDimension);

            if (savedPos != null) {
                // Optional safety: clamp Y; optional max distance
                PositionData adjusted = applySafety(newPlayer, savedPos);
                if (MultiWorldPositions.getConfig().restoreUseSafeLocation) {
                    adjusted = SafeLocationFinder.findSafe(newPlayer, newDimension, adjusted);
                }

                // Player is already in the target dimension; place safely and clear blockers if needed
                TeleportPlacement.placePlayerSafely(newPlayer, newDimension, adjusted);

                MultiWorldPositions.LOGGER.info("Restored position for {} in {}: {}",
                        newPlayer.getName().getString(), newDimension, adjusted);
            } else {
                MultiWorldPositions.LOGGER.info("No saved position for {} in {} (first visit)",
                        newPlayer.getName().getString(), newDimension);
            }
        } else {
            MultiWorldPositions.LOGGER.info("Skipped position restore for {} (hub world: {})",
                    newPlayer.getName().getString(), newDimension);
        }

        storage.savePlayerData(newPlayer.getUuid());
    }

    // Exposed for AFTER world-change event to reuse core logic
    public static void handleAfterWorldChange(ServerPlayerEntity player, ServerWorld origin, ServerWorld destination) {
        handleWithKeys(player,
                origin.getRegistryKey().getValue().toString(),
                destination.getRegistryKey().getValue().toString());
    }

    // Shared handler: assumes the old world's position was already saved (e.g., in BEFORE event)
    private static void handleWithKeys(ServerPlayerEntity player, String oldDimension, String newDimension) {
        PositionStorage storage = MultiWorldPositions.getPositionStorage();
        ModConfig config = MultiWorldPositions.getConfig();

        // Consume the one-time marker set by PortalLinkService to avoid restoring after portal-driven teleports
        if (PortalLinkService.consumePortalTransfer(player.getUuid())) {
            // Persist current state and skip further restore/redirect logic
            MultiWorldPositions.getPositionStorage().savePlayerData(player.getUuid());
            if (MultiWorldPositions.getConfig().debugMode) {
                MultiWorldPositions.LOGGER.debug("[MWP] Skipped restore after portal-driven teleport for {}", player.getGameProfile().getName());
            }
            return;
        }

        // Skip default-world redirect entirely for world-group destinations
        if (MultiWorldPositions.getConfig().findGroupByMember(newDimension) == null) {
            // Default-world redirect logic (config-gated)
            boolean newIsDefault = config.isDefaultWorld(newDimension);
            boolean cameFromNonDefault = !config.isDefaultWorld(oldDimension);
            if (newIsDefault && cameFromNonDefault && config.enableCrossDimRedirect) {
                String lastDefault = storage.getLastDefaultDimension(player.getUuid());
                if (lastDefault != null && !lastDefault.equals(newDimension)) {
                    PositionData saved = storage.getPosition(player.getUuid(), lastDefault);
                    if (saved != null) {
                        if (debounced(player.getUuid())) {
                            if (config.debugMode) {
                                MultiWorldPositions.LOGGER.debug("Debounced redirect for {}", player.getName().getString());
                            }
                        } else {
                            boolean success = crossDimTeleport(player, lastDefault, saved);
                            if (success) {
                                lastRedirectAt.put(player.getUuid(), System.currentTimeMillis());
                                MultiWorldPositions.LOGGER.info("Redirected {} to last default dimension {} and restored position",
                                        player.getName().getString(), lastDefault);
                                storage.savePlayerData(player.getUuid());
                                return;
                            } else if (config.failOpenOnTeleportError) {
                                MultiWorldPositions.LOGGER.warn("Cross-dimension redirect failed or not available; falling back to in-dimension restore for {}",
                                        player.getName().getString());
                            }
                        }
                    } else if (config.debugMode) {
                        MultiWorldPositions.LOGGER.debug("No saved position for last default {}. Skipping redirect.", lastDefault);
                    }
                }
                // fallthrough to in-dimension restore
            }
        }

        // Try to restore position in new dimension (unless it's a hub world)
        if (!config.isHubWorld(newDimension)) {
            PositionData savedPos = storage.getPosition(player.getUuid(), newDimension);

            if (savedPos != null) {
                // Exact restore with minimal local safety fallback
                TeleportPlacement.placeExactlyOrNearby(player, newDimension, savedPos);
                MultiWorldPositions.LOGGER.info("Restored position for {} in {}: {}",
                        player.getName().getString(), newDimension, savedPos);
            } else {
                // No saved position: if destination is part of a group and has configured spawn, use it; else world spawn
                WorldGroup g = MultiWorldPositions.getConfig().findGroupByMember(newDimension);
                PositionData target = null;
                if (g != null && g.spawnX != null && g.spawnY != null && g.spawnZ != null) {
                    target = new PositionData(g.spawnX, g.spawnY, g.spawnZ,
                            g.spawnYaw != null ? g.spawnYaw : player.getYaw(),
                            g.spawnPitch != null ? g.spawnPitch : player.getPitch());
                } else {
                    ServerWorld tw = player.getServerWorld();
                    var sp = tw.getSpawnPos();
                    target = new PositionData(sp.getX() + 0.5, sp.getY(), sp.getZ() + 0.5, player.getYaw(), player.getPitch());
                }
                TeleportPlacement.placeExactlyOrNearby(player, newDimension, target);
                MultiWorldPositions.LOGGER.info("Placed {} at spawn in {}: {}",
                        player.getName().getString(), newDimension, target);
            }
        } else {
            MultiWorldPositions.LOGGER.info("Skipped position restore for {} (hub world: {})",
                    player.getName().getString(), newDimension);
        }

        storage.savePlayerData(player.getUuid());
    }

    private static boolean debounced(UUID playerId) {
        Long last = lastRedirectAt.get(playerId);
        long now = System.currentTimeMillis();
        return last != null && (now - last) < REDIRECT_DEBOUNCE_MS;
    }

    private static PositionData applySafety(ServerPlayerEntity player, PositionData in) {
        ModConfig cfg = MultiWorldPositions.getConfig();
        double x = in.x;
        double y = in.y;
        double z = in.z;
        float yaw = in.yaw;
        float pitch = in.pitch;

        if (cfg.clampYToWorldBounds) {
            ServerWorld w = player.getServerWorld();
            double minY = w.getBottomY();
            double maxY = w.getBottomY() + w.getDimension().logicalHeight();
            if (y < minY) y = minY;
            if (y > maxY) y = maxY;
        }

        if (cfg.maxTeleportDistance >= 0) {
            double dx = x - player.getX();
            double dy = y - player.getY();
            double dz = z - player.getZ();
            double distSq = dx*dx + dy*dy + dz*dz;
            if (distSq > (cfg.maxTeleportDistance * cfg.maxTeleportDistance)) {
                MultiWorldPositions.LOGGER.warn("Skipping large in-dimension move for {} (distance={} > max={})",
                        player.getName().getString(), Math.sqrt(distSq), cfg.maxTeleportDistance);
                return new PositionData(player.getX(), player.getY(), player.getZ(), player.getYaw(), player.getPitch());
            }
        }

        return new PositionData(x, y, z, yaw, pitch);
    }

    private static boolean crossDimTeleport(ServerPlayerEntity player, String targetDimKey, PositionData pos) {
        // Resolve target world key for logging/validation
        try {
            Identifier id = Identifier.of(targetDimKey);
            RegistryKey<World> key = RegistryKey.of(RegistryKeys.WORLD, id);
            ServerWorld targetWorld = player.getServer().getWorld(key);
            if (targetWorld == null) {
                MultiWorldPositions.LOGGER.warn("Target world not found for {}", targetDimKey);
                return false;
            }
        } catch (Exception e) {
            MultiWorldPositions.LOGGER.warn("Invalid target dimension key: {}", targetDimKey);
            return false;
        }

        // Delegate to TeleportService
        boolean success = MultiWorldPositions.getTeleportService().teleport(player, targetDimKey);
        if (success) {
            // After cross-dimension move, place exactly or with minimal local adjustment
            TeleportPlacement.placeExactlyOrNearby(player, targetDimKey, pos);
        } else {
            MultiWorldPositions.LOGGER.info("[MWP] Intended cross-dimension redirect: {} -> {} at {}",
                    player.getName().getString(), targetDimKey, pos);
        }
        return success;
    }
}
