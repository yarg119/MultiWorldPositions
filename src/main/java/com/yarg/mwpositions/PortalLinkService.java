package com.yarg.mwpositions;

import net.minecraft.block.Blocks;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class PortalLinkService {
    private static final long COOLDOWN_TICKS = 60; // ~3s at 20 tps

    // Marker set to suppress one-time restore after portal-driven teleports
    private static final java.util.Set<java.util.UUID> suppressNextRestore = java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());
    public static void markPortalTransfer(java.util.UUID id) { suppressNextRestore.add(id); }
    public static boolean consumePortalTransfer(java.util.UUID id) { return suppressNextRestore.remove(id); }

    private final Map<UUID, Long> netherCooldown = new HashMap<>();
    private final Map<UUID, Long> endCooldown = new HashMap<>();
    private final Map<UUID, Integer> portalContactTicks = new HashMap<>();

    public void tick(MinecraftServer server) {
        ModConfig cfg = MultiWorldPositions.getConfig();
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            String fromDim = p.getWorld().getRegistryKey().getValue().toString();
            // Skip hubs
            if (cfg.isHubWorld(fromDim)) continue;
            WorldGroup g = cfg.findGroupByMember(fromDim);
            if (g == null) continue;

            boolean handled = false;
            boolean inPortal = inNetherPortal(p);
            // Warmup tracking similar to vanilla behavior
            if (inPortal) {
                int t = portalContactTicks.getOrDefault(p.getUuid(), 0) + 1;
                int cap = Math.max(1, cfg.portalWarmupTicks);
                if (t > cap) t = cap;
                portalContactTicks.put(p.getUuid(), t);
            } else {
                portalContactTicks.put(p.getUuid(), 0);
            }

            if (g.linkPortals != null && g.linkPortals.nether && inPortal) {
                int t = portalContactTicks.getOrDefault(p.getUuid(), 0);
                if (t >= cfg.portalWarmupTicks) {
                    handled = handleNetherPortal(p, g, fromDim);
                }
            }
            // Fallback: only when enabled AND standing in lit portal AND complete frame detected
            if (!handled && g.linkPortals != null && g.linkPortals.nether && cfg.fallbackDetectFrames && inPortal) {
                var fb = PortalFrameUtils.findNetherFrameBounds((net.minecraft.server.world.ServerWorld) p.getWorld(), p.getBlockPos());
                if (fb != null) {
                    int t = portalContactTicks.getOrDefault(p.getUuid(), 0);
                    if (t >= cfg.portalWarmupTicks) {
                        handled = handleNetherPortal(p, g, fromDim);
                    }
                }
            }
            // End portal visualization after completion: if vanilla didn't create it and allowed, fill interior at y-1
            if (g.linkPortals != null && g.linkPortals.end && g.createPortalIfMissing) {
                try {
                    var sw = (net.minecraft.server.world.ServerWorld) p.getWorld();
                    if (PortalFrameUtils.isEndRingComplete(sw, p.getBlockPos())) {
                        PortalFrameUtils.fillEndPortalInterior(sw, p.getBlockPos());
                    }
                } catch (Throwable t) {
                    if (cfg.debugMode) {
                        MultiWorldPositions.LOGGER.debug("[MWP] End portal visualization check failed: {}", t.toString());
                    }
                }
            }
            if (!handled && g.linkPortals != null && g.linkPortals.end && inEndPortal(p)) {
                handleEndPortal(p, g, fromDim);
            }
        }
    }

    private boolean handleNetherPortal(ServerPlayerEntity player, WorldGroup group, String fromDim) {
        if (!cooldownOk(player, PortalKind.NETHER)) return false;
        String targetDim = MultiWorldPositions.getConfig().nextForPortal(group, fromDim, PortalKind.NETHER);
        if (targetDim == null) {
            if (MultiWorldPositions.getConfig().debugMode) {
                MultiWorldPositions.LOGGER.debug("[MWP] PortalLink(Nether): no target for fromDim={} in group {} (overworld={}, nether={})",
                        fromDim, group.id, group.overworld, group.nether);
            }
            return false;
        }
        if (MultiWorldPositions.getConfig().debugMode) {
            MultiWorldPositions.LOGGER.debug("[MWP] PortalLink(Nether): fromDim={} -> targetDim={} (group={}, overworld={}, nether={})",
                    fromDim, targetDim, group.id, group.overworld, group.nether);
        }

        // Inline inventory swap for robustness
        InventoryStorage inv = MultiWorldPositions.getInventoryStorage();
        ModConfig cfg = MultiWorldPositions.getConfig();
        String originGroup = cfg.resolveInventoryGroupId(fromDim);
        boolean originProfile = false;
        if (group != null) originProfile = group.inventoryProfile;
        else if ("__default".equals(originGroup)) originProfile = cfg.inventoryProfileForDefaultWorlds;
        else if ("__ungrouped".equals(originGroup)) originProfile = cfg.inventoryProfileForUngrouped;
        if (originGroup != null && originProfile) {
            MultiWorldPositions.LOGGER.debug("[MWP] InvSwap: save origin group {} for {}", originGroup, player.getName().getString());
            inv.saveForGroup(player, originGroup);
        }

        PositionData pos = computeNetherLinkedCoords(player, group, fromDim);
        PortalLinkService.markPortalTransfer(player.getUuid());
        if (MultiWorldPositions.getTeleportService().teleport(player, targetDim)) {
            // Update last visited member for this group immediately
            WorldGroup gg = MultiWorldPositions.getConfig().findGroupByMember(targetDim);
            if (gg != null && gg.id != null) {
                MultiWorldPositions.getPositionStorage().setLastGroupMember(player.getUuid(), gg.id, targetDim);
            }
            // After cross-dim hop, load destination inventory if profiled
            String destGroup = cfg.resolveInventoryGroupId(targetDim);
            boolean destProfile = false;
            if (group != null) destProfile = group.inventoryProfile;
            else if ("__default".equals(destGroup)) destProfile = cfg.inventoryProfileForDefaultWorlds;
            else if ("__ungrouped".equals(destGroup)) destProfile = cfg.inventoryProfileForUngrouped;
            if (destGroup != null && destProfile) {
                MultiWorldPositions.LOGGER.debug("[MWP] InvSwap: load dest group {} for {}", destGroup, player.getName().getString());
                inv.loadForGroup(player, destGroup);
            }
            // Ensure a return portal exists (or snap to nearest) in the destination world
            try {
                if (cfg.createReturnPortal) {
                    var desired = net.minecraft.util.math.BlockPos.ofFloored(pos.x, pos.y, pos.z);
                    final float yaw = player.getYaw();
                    final String targetDimFinal = targetDim;
                    final PositionData scheduledPos = pos;
                    // Defer heavy search/snap to next tick
                    player.getServer().execute(() -> {
                        try {
                            ServerWorld targetWorld = player.getServerWorld();
                            var axisPref = PortalSpawnHelper.yawToAxis(yaw);
                            PortalFrameUtils.FrameBounds preferred = new PortalFrameUtils.FrameBounds(axisPref, 0, 0, 0, 0, 0, 0);
                            int searchRadius = 128; // vanilla closest-portal search radius
                            var portalCell = PortalBuilder.ensureReturnPortal(targetWorld, desired, preferred, searchRadius);
                            PositionData finalPos2 = (portalCell != null)
                                    ? SafeLocationFinder.findSafeNear(player, targetDimFinal, scheduledPos, portalCell)
                                    : SafeLocationFinder.findSafe(player, targetDimFinal, scheduledPos);
                            // Safe placement + mob clearance
                            TeleportPlacement.placePlayerSafely(player, targetDimFinal, finalPos2);
                            setCooldown(player, PortalKind.NETHER);
                            MultiWorldPositions.getPositionStorage().savePlayerData(player.getUuid());
                        } catch (Throwable t) {
                            if (cfg.debugMode) {
                                MultiWorldPositions.LOGGER.debug("[MWP] deferred ensureReturnPortal failed: {}", t.toString());
                            }
                            TeleportPlacement.placePlayerSafely(player, targetDimFinal, scheduledPos);
                            setCooldown(player, PortalKind.NETHER);
                            MultiWorldPositions.getPositionStorage().savePlayerData(player.getUuid());
                        }
                    });
                    return true; // early return; placement happens next tick
                }
            } catch (Throwable t) {
                if (cfg.debugMode) {
                    MultiWorldPositions.LOGGER.debug("[MWP] ensureReturnPortal scheduling failed: {}", t.toString());
                }
            }
            // If not creating/scheduling return portal, place immediately
            TeleportPlacement.placePlayerSafely(player, targetDim, pos);
            setCooldown(player, PortalKind.NETHER);
            MultiWorldPositions.getPositionStorage().savePlayerData(player.getUuid());
            return true;
        }
        return false;
    }

    private boolean handleEndPortal(ServerPlayerEntity player, WorldGroup group, String fromDim) {
        if (!cooldownOk(player, PortalKind.END)) return false;
        String targetDim = MultiWorldPositions.getConfig().nextForPortal(group, fromDim, PortalKind.END);
        if (targetDim == null) {
            if (MultiWorldPositions.getConfig().debugMode) {
                MultiWorldPositions.LOGGER.debug("[MWP] PortalLink(End): no target for fromDim={} in group {} (overworld={}, end={})",
                        fromDim, group.id, group.overworld, group.end);
            }
            return false;
        }
        if (MultiWorldPositions.getConfig().debugMode) {
            MultiWorldPositions.LOGGER.debug("[MWP] PortalLink(End): fromDim={} -> targetDim={} (group={}, overworld={}, end={})",
                    fromDim, targetDim, group.id, group.overworld, group.end);
        }

        // Inline inventory swap for robustness
        InventoryStorage inv = MultiWorldPositions.getInventoryStorage();
        ModConfig cfg = MultiWorldPositions.getConfig();
        String originGroup = cfg.resolveInventoryGroupId(fromDim);
        boolean originProfile = false;
        if (group != null) originProfile = group.inventoryProfile;
        else if ("__default".equals(originGroup)) originProfile = cfg.inventoryProfileForDefaultWorlds;
        else if ("__ungrouped".equals(originGroup)) originProfile = cfg.inventoryProfileForUngrouped;
        if (originGroup != null && originProfile) {
            MultiWorldPositions.LOGGER.debug("[MWP] InvSwap: save origin group {} for {}", originGroup, player.getName().getString());
            inv.saveForGroup(player, originGroup);
        }

        PositionData pos = computeEndLinkedCoords(player, group, fromDim);
        PortalLinkService.markPortalTransfer(player.getUuid());
        if (MultiWorldPositions.getTeleportService().teleport(player, targetDim)) {
            // Update last visited member for this group immediately
            WorldGroup gg = MultiWorldPositions.getConfig().findGroupByMember(targetDim);
            if (gg != null && gg.id != null) {
                MultiWorldPositions.getPositionStorage().setLastGroupMember(player.getUuid(), gg.id, targetDim);
            }
            String destGroup = cfg.resolveInventoryGroupId(targetDim);
            boolean destProfile = false;
            if (group != null) destProfile = group.inventoryProfile;
            else if ("__default".equals(destGroup)) destProfile = cfg.inventoryProfileForDefaultWorlds;
            else if ("__ungrouped".equals(destGroup)) destProfile = cfg.inventoryProfileForUngrouped;
            if (destGroup != null && destProfile) {
                MultiWorldPositions.LOGGER.debug("[MWP] InvSwap: load dest group {} for {}", destGroup, player.getName().getString());
                inv.loadForGroup(player, destGroup);
            }
            // Build/ensure a platform in the End and optionally spawn the dragon, then place safely
            try {
                ServerWorld targetWorld = player.getServerWorld();
                var desired = net.minecraft.util.math.BlockPos.ofFloored(pos.x, pos.y, pos.z);
                if (cfg.endCreateArrivalPlatform) {
                    var platformTop = EndArrivalHelper.ensureArrivalPlatform(targetWorld, desired);
                    if (cfg.endSpawnDragonOnArrival) {
                        EndArrivalHelper.spawnDragonIfEnabled(targetWorld);
                    }
                    pos = new PositionData(platformTop.getX() + 0.5, platformTop.getY(), platformTop.getZ() + 0.5, player.getYaw(), player.getPitch());
                }
            } catch (Throwable t) {
                if (cfg.debugMode) {
                    MultiWorldPositions.LOGGER.debug("[MWP] End arrival platform setup failed: {}", t.toString());
                }
            }
            TeleportPlacement.placePlayerSafely(player, targetDim, pos);
            setCooldown(player, PortalKind.END);
            MultiWorldPositions.getPositionStorage().savePlayerData(player.getUuid());
            return true;
        }
        return false;
    }

    private boolean inNetherPortal(ServerPlayerEntity p) {
        var w = p.getWorld();
        var bp = p.getBlockPos();
        return w.getBlockState(bp).isOf(Blocks.NETHER_PORTAL)
                || w.getBlockState(bp.up()).isOf(Blocks.NETHER_PORTAL);
    }

    private boolean inEndPortal(ServerPlayerEntity p) {
        var w = p.getWorld();
        var bp = p.getBlockPos();
        return w.getBlockState(bp).isOf(Blocks.END_PORTAL)
                || w.getBlockState(bp.down()).isOf(Blocks.END_PORTAL);
    }

    // Heuristic: detect if player stands inside an obsidian-bounded cavity resembling a nether frame
    private boolean inNetherFrameCavity(ServerPlayerEntity p) {
        var w = p.getWorld();
        var bp = p.getBlockPos();
        var stateAt = w.getBlockState(bp);
        // Only consider if player is in air, cave air, void air, or fire inside the frame
        boolean insideCell = stateAt.isAir() || stateAt.isOf(Blocks.CAVE_AIR) || stateAt.isOf(Blocks.VOID_AIR) || stateAt.isOf(Blocks.FIRE);
        if (!insideCell) return false;
        int obsidianCount = 0;
        // Sample a small cross around the player up to distance 2 in X/Z at feet level
        int[][] offsets = new int[][] { {-1,0}, {-2,0}, {1,0}, {2,0}, {0,-1}, {0,-2}, {0,1}, {0,2} };
        for (int[] off : offsets) {
            if (w.getBlockState(bp.add(off[0], 0, off[1])).isOf(Blocks.OBSIDIAN)) obsidianCount++;
        }
        // Also check vertical neighbors one block above and below for frame edges
        if (w.getBlockState(bp.add(-1, 1, 0)).isOf(Blocks.OBSIDIAN)) obsidianCount++;
        if (w.getBlockState(bp.add(1, 1, 0)).isOf(Blocks.OBSIDIAN)) obsidianCount++;
        if (w.getBlockState(bp.add(0, 1, -1)).isOf(Blocks.OBSIDIAN)) obsidianCount++;
        if (w.getBlockState(bp.add(0, 1, 1)).isOf(Blocks.OBSIDIAN)) obsidianCount++;
        if (w.getBlockState(bp.add(-1, -1, 0)).isOf(Blocks.OBSIDIAN)) obsidianCount++;
        if (w.getBlockState(bp.add(1, -1, 0)).isOf(Blocks.OBSIDIAN)) obsidianCount++;
        if (w.getBlockState(bp.add(0, -1, -1)).isOf(Blocks.OBSIDIAN)) obsidianCount++;
        if (w.getBlockState(bp.add(0, -1, 1)).isOf(Blocks.OBSIDIAN)) obsidianCount++;
        // Threshold: if we see enough obsidian around, assume we're inside a frame cavity
        return obsidianCount >= 4; // conservative threshold
    }

    private PositionData computeNetherLinkedCoords(ServerPlayerEntity p, WorldGroup g, String fromDim) {
        double x = p.getX();
        double y = p.getY(); // Y is 1:1 in vanilla
        double z = p.getZ();

        ModConfig cfg = MultiWorldPositions.getConfig();
        boolean fromIsOW = cfg.isGroupOverworld(g, fromDim);

        // Vanilla scale: Overworld -> Nether = 0.125; Nether -> Overworld = 8.0
        double scale = fromIsOW ? 0.125 : 8.0;

        return new PositionData(x * scale, y, z * scale, p.getYaw(), p.getPitch());
    }

    private PositionData computeEndLinkedCoords(ServerPlayerEntity p, WorldGroup g, String fromDim) {
        return new PositionData(p.getX(), p.getY(), p.getZ(), p.getYaw(), p.getPitch());
    }

    private boolean cooldownOk(ServerPlayerEntity p, PortalKind kind) {
        long tick = p.getServer().getTicks();
        Map<UUID, Long> map = (kind == PortalKind.NETHER) ? netherCooldown : endCooldown;
        Long last = map.get(p.getUuid());
        return last == null || (tick - last) >= COOLDOWN_TICKS;
    }

    private void setCooldown(ServerPlayerEntity p, PortalKind kind) {
        long tick = p.getServer().getTicks();
        if (kind == PortalKind.NETHER) {
            netherCooldown.put(p.getUuid(), tick);
        } else {
            endCooldown.put(p.getUuid(), tick);
        }
    }

    private PositionData clampYIfNeeded(ServerPlayerEntity player, PositionData in) {
        ModConfig cfg = MultiWorldPositions.getConfig();
        if (!cfg.clampYToWorldBounds) return in;
        double y = in.y;
        if (y < 0) y = 0;
        if (y > 320) y = 320;
        return new PositionData(in.x, y, in.z, in.yaw, in.pitch);
    }
}
