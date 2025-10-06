package com.yarg.mwpositions;

import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityWorldChangeEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.minecraft.item.Items;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.ActionResult;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 1.21.9 overlay: same behavior; updated player name logging and server/world accessors.
 */
public class MultiWorldPositions implements DedicatedServerModInitializer {
    public static final String MOD_ID = "multiworldpositions";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static PositionStorage positionStorage;
    private static InventoryStorage inventoryStorage;
    private static ModConfig config;
    private static TeleportService teleportService;
    private static PortalLinkService portalLinkService;
    private static final java.util.Map<java.util.UUID, Long> specialPortalCd = new java.util.HashMap<>();

    @Override
    public void onInitializeServer() {
        LOGGER.info("Initializing MultiWorld Positions Tracker");
        config = ModConfig.load();
        positionStorage = new PositionStorage();
        inventoryStorage = new InventoryStorage();
        teleportService = new FabricTeleportService();
        portalLinkService = config.enablePortals ? new PortalLinkService() : null;

        DimensionChangeListener.register();

        ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD.register((player, origin, destination) -> {
            String originKey = origin.getRegistryKey().getValue().toString();
            String destKey = destination.getRegistryKey().getValue().toString();
            getPositionStorage().saveCachedOriginIfMatches(player, originKey);

            ModConfig cfg = getConfig();
            boolean originIsEnd = "minecraft:the_end".equals(originKey);
            boolean destIsOw   = "minecraft:overworld".equals(destKey);
            if (originIsEnd && destIsOw) {
                PortalLinkService.markPortalTransfer(player.getUuid());
                if (cfg.debugMode) {
                    LOGGER.debug("[MWP] Marked portal transfer (vanilla End exit) for {}: {} -> {}",
                        player.getName().getString(), originKey, destKey);
                }
            }

            if (!cfg.enablePortals) {
                WorldGroup g = cfg.findGroupByMember(originKey);
                if (g != null && g.linkPortals != null) {
                    if (g.linkPortals.nether) {
                        String expectedNether = cfg.nextForPortal(g, originKey, PortalKind.NETHER);
                        if (expectedNether != null
                                && expectedNether.equals(destKey)
                                && (getPositionStorage().wasInNetherPortalCell(player.getUuid())
                                    || getPositionStorage().hadEnderPearlCooldown(player.getUuid()))) {
                            PortalLinkService.markPortalTransfer(player.getUuid());
                            if (cfg.debugMode) {
                                LOGGER.debug("[MWP] Marked portal transfer (vanilla Nether) for {}: {} -> {}",
                                        player.getName().getString(), originKey, destKey);
                            }
                        }
                    }
                    if (g.linkPortals.end) {
                        String expectedEnd = cfg.nextForPortal(g, originKey, PortalKind.END);
                        if (expectedEnd != null
                                && expectedEnd.equals(destKey)
                                && getPositionStorage().wasInEndPortalCell(player.getUuid())) {
                            PortalLinkService.markPortalTransfer(player.getUuid());
                            if (cfg.debugMode) {
                                LOGGER.debug("[MWP] Marked portal transfer (vanilla End) for {}: {} -> {}",
                                        player.getName().getString(), originKey, destKey);
                            }
                        }
                    }
                }
            }

            if (cfg.enablePortals) {
                WorldGroup og = cfg.findGroupByMember(originKey);
                if (og != null && og.linkPortals != null && og.linkPortals.nether) {
                    String shouldBe = cfg.nextForPortal(og, originKey, PortalKind.NETHER);
                    if (shouldBe != null && !shouldBe.equals(destKey) && cfg.isDefaultWorld(destKey)) {
                        String originInvGroup = cfg.resolveInventoryGroupId(originKey);
                        boolean originProfile = (og.inventoryProfile)
                                || ("__default".equals(originInvGroup) && cfg.inventoryProfileForDefaultWorlds)
                                || ("__ungrouped".equals(originInvGroup) && cfg.inventoryProfileForUngrouped);
                        if (originInvGroup != null && originProfile) getInventoryStorage().saveForGroup(player, originInvGroup);

                        double scale = cfg.isGroupOverworld(og, originKey) ? 0.125 : 8.0;
                        PositionData pos = new PositionData(player.getX() * scale, player.getY(), player.getZ() * scale, player.getYaw(), player.getPitch());

                        if (getTeleportService().teleport(player, shouldBe)) {
                            String destInvGroup = cfg.resolveInventoryGroupId(shouldBe);
                            boolean destProfile = (og.inventoryProfile)
                                    || ("__default".equals(destInvGroup) && cfg.inventoryProfileForDefaultWorlds)
                                    || ("__ungrouped".equals(destInvGroup) && cfg.inventoryProfileForUngrouped);
                            if (destInvGroup != null && destProfile) getInventoryStorage().loadForGroup(player, destInvGroup);

                            ServerWorld targetWorld = player.getEntityWorld();
                            var desired = net.minecraft.util.math.BlockPos.ofFloored(pos.x, pos.y, pos.z);
                            final PositionData scheduledPos = pos;
                            final float yaw = player.getYaw();
                            player.getCommandSource().getServer().execute(() -> {
                                try {
                                    int searchRadius = 128;
                                    var axisPref = PortalSpawnHelper.yawToAxis(yaw);
                                    PortalFrameUtils.FrameBounds preferred = new PortalFrameUtils.FrameBounds(axisPref, 0, 0, 0, 0, 0, 0);
                                    var portalCell = PortalBuilder.ensureReturnPortal(targetWorld, desired, preferred, searchRadius);
                                    PositionData finalPos = (portalCell != null)
                                        ? SafeLocationFinder.findSafeNear(player, shouldBe, scheduledPos, portalCell)
                                        : SafeLocationFinder.findSafe(player, shouldBe, scheduledPos);
                                    TeleportPlacement.placePlayerSafely(player, shouldBe, finalPos);
                                } catch (Throwable t) {
                                    if (cfg.debugMode) LOGGER.debug("[MWP] Correction ensureReturnPortal failed: {}", t.toString());
                                    TeleportPlacement.placePlayerSafely(player, shouldBe, scheduledPos);
                                }
                            });
                            return;
                        }
                    }
                }
            }

            String originGroup = config.resolveInventoryGroupId(originKey);
            String destGroup = config.resolveInventoryGroupId(destKey);

            if (originGroup != null && !originGroup.equals(destGroup)) {
                boolean originProfile = false;
                WorldGroup og2 = config.findGroupByMember(originKey);
                if (og2 != null) originProfile = og2.inventoryProfile;
                else if ("__default".equals(originGroup)) originProfile = config.inventoryProfileForDefaultWorlds;
                else if ("__ungrouped".equals(originGroup)) originProfile = config.inventoryProfileForUngrouped;

                if (originProfile) {
                    if (config.debugMode) {
                        LOGGER.debug("[MWP] InvSwap: save {} for {}", originGroup, player.getName().getString());
                    }
                    inventoryStorage.saveForGroup(player, originGroup);
                }
            }
            if (destGroup != null && !destGroup.equals(originGroup)) {
                boolean destProfile = false;
                WorldGroup dg = config.findGroupByMember(destKey);
                if (dg != null) destProfile = dg.inventoryProfile;
                else if ("__default".equals(destGroup)) destProfile = config.inventoryProfileForDefaultWorlds;
                else if ("__ungrouped".equals(destGroup)) destProfile = config.inventoryProfileForUngrouped;

                if (destProfile) {
                    if (config.debugMode) {
                        LOGGER.debug("[MWP] InvSwap: load {} for {}", destGroup, player.getName().getString());
                    }
                    inventoryStorage.loadForGroup(player, destGroup);
                }
            }

            DimensionChangeListener.handleAfterWorldChange(player, origin, destination);
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
                positionStorage.updateLastKnown(p);
            }
            if (config.enablePortals && portalLinkService != null) {
                portalLinkService.tick(server);
            }
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            MwpCommands.register(dispatcher);
        });

        if (config.enablePortals) {
            UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
                if (world.isClient()) return ActionResult.PASS;
                ModConfig cfg = getConfig();
                String dim = world.getRegistryKey().getValue().toString();
                WorldGroup g = cfg.findGroupByMember(dim);
                if (g == null) return ActionResult.PASS;

                var stack = player.getStackInHand(hand);
                var pos = hit.getBlockPos();
                var state = world.getBlockState(pos);

                if (cfg.debugMode) {
                    boolean validFrameDbg = PortalFrameUtils.isValidNetherFrame((ServerWorld) world, pos);
                    boolean vanillaWillLightDbg = PortalFrameUtils.vanillaWillCreatePortal((ServerWorld) world, pos);
                    MultiWorldPositions.LOGGER.debug("[MWP] UseBlock: dim={}, item={}, state={}, validFrame={}, vanillaWillLight={}, createPortalIfMissing={}",
                            dim,
                            stack.getItem().toString(),
                            state.getBlock().toString(),
                            validFrameDbg,
                            vanillaWillLightDbg,
                            g.createPortalIfMissing);
                }

                if (g.linkPortals != null && g.linkPortals.nether && stack.isOf(Items.FLINT_AND_STEEL)) {
                    if (PortalFrameUtils.isValidNetherFrame((ServerWorld) world, pos)) {
                        boolean vanillaWillLight = PortalFrameUtils.vanillaWillCreatePortal((ServerWorld) world, pos);
                        if (vanillaWillLight) {
                            return ActionResult.PASS;
                        } else if (g.createPortalIfMissing) {
                            ServerWorld sw = (ServerWorld) world;
                            boolean filled = PortalFrameUtils.fillNetherPortalInterior(sw, pos)
                                    || PortalFrameUtils.fillNetherPortalInterior(sw, pos.up())
                                    || PortalFrameUtils.fillNetherPortalInterior(sw, pos.offset(hit.getSide()));
                            if (filled) {
                                return ActionResult.SUCCESS;
                            }
                        }
                        return ActionResult.PASS;
                    }
                }

                if (g.linkPortals != null && g.linkPortals.end && stack.isOf(Items.ENDER_EYE)) {
                    return ActionResult.PASS;
                }

                return ActionResult.PASS;
            });

            AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> {
                if (world.isClient()) return ActionResult.PASS;
                ModConfig cfg = getConfig();
                if (!cfg.specialPortalEnabled) return ActionResult.PASS;

                var stack = player.getStackInHand(hand);
                Item trigger = Registries.ITEM.get(Identifier.of(cfg.specialPortalItemId));
                if (!stack.isOf(trigger)) return ActionResult.PASS;

                String dim = world.getRegistryKey().getValue().toString();
                WorldGroup g = cfg.findGroupByMember(dim);
                if (g == null || g.linkPortals == null || !g.linkPortals.nether) {
                    if (cfg.debugMode) LOGGER.debug("[MWP] SpecialPortal: no group or nether linking disabled for {}", dim);
                    return ActionResult.PASS;
                }

                ServerPlayerEntity sp = (ServerPlayerEntity) player;
                long now = sp.getCommandSource().getServer().getTicks();
                Long last = specialPortalCd.get(sp.getUuid());
                if (last != null && (now - last) < cfg.specialPortalCooldownTicks) {
                    return ActionResult.FAIL;
                }

                net.minecraft.util.math.BlockPos corner = (direction == net.minecraft.util.math.Direction.UP) ? pos.up() : pos.offset(direction);
                boolean success = false;
                try {
                    net.minecraft.util.math.Direction.Axis axisPref = PortalSpawnHelper.yawToAxis(sp.getYaw());
                    PortalSpawnHelper.SpawnResult sr = PortalSpawnHelper.buildAtCorner((ServerWorld) world, corner, axisPref);
                    if (!sr.success) {
                        net.minecraft.util.math.Direction.Axis other = (axisPref == net.minecraft.util.math.Direction.Axis.X)
                                ? net.minecraft.util.math.Direction.Axis.Z : net.minecraft.util.math.Direction.Axis.X;
                        sr = PortalSpawnHelper.buildAtCorner((ServerWorld) world, corner, other);
                    }
                    if (sr.success) {
                        specialPortalCd.put(sp.getUuid(), now);
                        success = true;
                        sp.sendMessage(net.minecraft.text.Text.of("A Nether portal materializes."), false);

                        if (cfg.specialPortalEnsureReturn) {
                            String targetDim = cfg.nextForPortal(g, dim, PortalKind.NETHER);
                            if (targetDim != null) {
                                double scale = cfg.isGroupOverworld(g, dim) ? 0.125 : 8.0;
                                PositionData scaled = new PositionData(sp.getX() * scale, sp.getY(), sp.getZ() * scale, sp.getYaw(), sp.getPitch());

                                final PortalFrameUtils.FrameBounds fbFinal = sr.frameBounds;

                                sp.getCommandSource().getServer().execute(() -> {
                                    try {
                                        var id = net.minecraft.util.Identifier.of(targetDim);
                                        var key = net.minecraft.registry.RegistryKey.of(net.minecraft.registry.RegistryKeys.WORLD, id);
                                        net.minecraft.server.world.ServerWorld targetWorld = sp.getCommandSource().getServer().getWorld(key);
                                        if (targetWorld == null) return;

                                        var desired = net.minecraft.util.math.BlockPos.ofFloored(scaled.x, scaled.y, scaled.z);
                                        PortalFrameUtils.FrameBounds pref = fbFinal != null ? fbFinal
                                                : new PortalFrameUtils.FrameBounds(PortalSpawnHelper.yawToAxis(sp.getYaw()), 0, 0, 0, 0, 0, 0);

                                        int search = Math.min(96, Math.max(32, MultiWorldPositions.getConfig().returnPortalSearchRadius));
                                        PortalBuilder.ensureReturnPortal(targetWorld, desired, pref, search);
                                    } catch (Throwable t) {
                                        if (cfg.debugMode) LOGGER.debug("[MWP] Deferred ensureReturnPortal failed: {}", t.toString());
                                    }
                                });
                            }
                        }
                    }
                } catch (Throwable t) {
                    if (cfg.debugMode) LOGGER.debug("[MWP] Special portal error: {}", t.toString());
                }

                return success ? ActionResult.SUCCESS : ActionResult.PASS;
            });
        }

        // Save position when player disconnects
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            var player = handler.getPlayer();
            String dimensionKey = player.getEntityWorld().getRegistryKey().getValue().toString();

            // Don't save position if in a hub world
            if (!config.isHubWorld(dimensionKey)) {
                positionStorage.savePosition(player);
                LOGGER.info("Saved disconnect position for {} in {}",
                        player.getName().getString(), dimensionKey);
            } else {
                LOGGER.info("Skipped saving position for {} (in hub world: {})",
                        player.getName().getString(), dimensionKey);
            }

            // Persist inventories for current group if applicable
            String gid = config.getGroupIdForWorld(dimensionKey);
            if (gid != null) {
                WorldGroup g = config.findGroupByMember(dimensionKey);
                if (g != null && g.inventoryProfile) {
                    inventoryStorage.saveForGroup(player, gid);
                }
            }

            // Write to disk
            positionStorage.savePlayerData(player.getUuid());
        });

        // Load player data when they join
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayerEntity player = handler.getPlayer();
            positionStorage.loadPlayerData(player.getUuid());
            // Load inventory profile for current group if enabled
            String dim = player.getEntityWorld().getRegistryKey().getValue().toString();
            String gid = config.getGroupIdForWorld(dim);
            if (gid != null) {
                WorldGroup g = config.findGroupByMember(dim);
                if (g != null && g.inventoryProfile) {
                    inventoryStorage.loadForGroup(player, gid);
                }
            }
        });

        // Save all data before shutdown
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            try {
                positionStorage.saveAll(server);
            } catch (Throwable t) {
                LOGGER.error("[MWP] Failed to save all positions on shutdown", t);
            }
        });
    }

    public static PositionStorage getPositionStorage() { return positionStorage; }
    public static InventoryStorage getInventoryStorage() { return inventoryStorage; }
    public static ModConfig getConfig() { return config; }
    public static TeleportService getTeleportService() { return teleportService; }
}
