package com.yarg.mwpositions;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.registry.RegistryKey;
import net.minecraft.world.World;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class PositionStorage {
    // Transient cache of last known player position per tick (not persisted)
    private static class LastKnown {
        final String dimensionKey;
        final PositionData pos;
        final boolean inNetherPortalCell; // NEW: nether portal at feet/head
        final boolean inEndPortalCell;    // NEW: end portal at feet/below
        final boolean coolingEnderPearl;  // NEW: player has ender pearl cooldown
        LastKnown(String dimensionKey, PositionData pos, boolean inNetherPortalCell, boolean inEndPortalCell, boolean coolingEnderPearl) {
            this.dimensionKey = dimensionKey;
            this.pos = pos;
            this.inNetherPortalCell = inNetherPortalCell;
            this.inEndPortalCell = inEndPortalCell;
            this.coolingEnderPearl = coolingEnderPearl;
        }
    }

    private final Map<UUID, LastKnown> lastKnownByPlayer = new HashMap<>();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path STORAGE_PATH = Path.of("config", "worldpositions");

    private final Map<UUID, Map<String, PositionData>> playerPositions = new HashMap<>();
    // New: track last default dimension per player (minecraft:overworld|the_nether|the_end)
    private final Map<UUID, String> lastDefaultDimByPlayer = new HashMap<>();
    // New: track last group member dimension per player: groupId -> dimensionKey
    private final Map<UUID, Map<String, String>> lastGroupMemberByPlayer = new HashMap<>();

    // Wrapper for persisted data (new schema)
    private static class PlayerPositionsFile {
        Map<String, PositionData> positions = new HashMap<>();
        String lastDefaultDimension; // nullable
        Map<String, String> lastGroupMember; // nullable: groupId -> dimensionKey
    }

    public PositionStorage() {
        try {
            Files.createDirectories(STORAGE_PATH);
            MultiWorldPositions.LOGGER.info("Position storage directory ready at: {}", STORAGE_PATH);
        } catch (IOException e) {
            MultiWorldPositions.LOGGER.error("Failed to create storage directory", e);
        }
    }

    public void savePosition(ServerPlayerEntity player) {
        UUID playerId = player.getUuid();
        RegistryKey<World> worldKey = player.getWorld().getRegistryKey();
        String dimensionKey = worldKey.getValue().toString();

        PositionData position = new PositionData(
                player.getX(),
                player.getY(),
                player.getZ(),
                player.getYaw(),
                player.getPitch()
        );

        playerPositions.computeIfAbsent(playerId, k -> new HashMap<>())
                .put(dimensionKey, position);

        // Update last default dimension if applicable
        if (MultiWorldPositions.getConfig().isDefaultWorld(dimensionKey)) {
            lastDefaultDimByPlayer.put(playerId, dimensionKey);
            if (MultiWorldPositions.getConfig().debugMode) {
                MultiWorldPositions.LOGGER.debug("Updated last default dimension for {} to {}",
                        player.getName().getString(), dimensionKey);
            }
        }

        // Update last group member dimension when applicable (non-hub worlds only)
        ModConfig cfg = MultiWorldPositions.getConfig();
        if (!cfg.isHubWorld(dimensionKey)) {
            WorldGroup g = cfg.findGroupByMember(dimensionKey);
            if (g != null && g.id != null) {
                setLastGroupMember(playerId, g.id, dimensionKey);
                if (cfg.debugMode) {
                    MultiWorldPositions.LOGGER.debug("[MWP] Updated last group member: {} -> {}",
                            g.id, dimensionKey);
                }
            }
        }

        if (MultiWorldPositions.getConfig().debugMode) {
            MultiWorldPositions.LOGGER.debug("Saved position for {} in {}: {}",
                    player.getName().getString(), dimensionKey, position);
        }
    }

    public PositionData getPosition(UUID playerId, String dimensionKey) {
        return playerPositions.getOrDefault(playerId, new HashMap<>()).get(dimensionKey);
    }

    public boolean hasPosition(UUID playerId, String dimensionKey) {
        return playerPositions.containsKey(playerId) &&
                playerPositions.get(playerId).containsKey(dimensionKey);
    }

    public String getLastDefaultDimension(UUID playerId) {
        return lastDefaultDimByPlayer.get(playerId);
    }

    public void savePlayerData(UUID playerId) {
        Map<String, PositionData> positions = playerPositions.get(playerId);
        if (positions == null || positions.isEmpty()) {
            return;
        }

        try {
            Path playerFile = STORAGE_PATH.resolve(playerId.toString() + ".json");

            PlayerPositionsFile out = new PlayerPositionsFile();
            out.positions = positions;
            out.lastDefaultDimension = lastDefaultDimByPlayer.get(playerId);
            out.lastGroupMember = lastGroupMemberByPlayer.get(playerId);

            String json = GSON.toJson(out);
            Files.writeString(playerFile, json);

            if (MultiWorldPositions.getConfig().debugMode) {
                MultiWorldPositions.LOGGER.debug("Saved {} positions for player {} (lastDefault={})",
                        positions.size(), playerId, out.lastDefaultDimension);
            }
        } catch (IOException e) {
            MultiWorldPositions.LOGGER.error("Failed to save player data for {}", playerId, e);
        }
    }

    public void loadPlayerData(UUID playerId) {
        try {
            Path playerFile = STORAGE_PATH.resolve(playerId.toString() + ".json");

            if (Files.exists(playerFile)) {
                String json = Files.readString(playerFile);

                // Try new schema first
                PlayerPositionsFile file = GSON.fromJson(json, PlayerPositionsFile.class);
                if (file != null && file.positions != null && !file.positions.isEmpty()) {
                    playerPositions.put(playerId, file.positions);
                    if (file.lastDefaultDimension != null) {
                        lastDefaultDimByPlayer.put(playerId, file.lastDefaultDimension);
                    }
                    if (file.lastGroupMember != null) {
                        lastGroupMemberByPlayer.put(playerId, file.lastGroupMember);
                    }
                    MultiWorldPositions.LOGGER.info("Loaded {} positions for player {} (lastDefault={})",
                            file.positions.size(), playerId, file.lastDefaultDimension);
                    return;
                }

                // Fallback to legacy schema: Map<String, PositionData>
                Map<String, PositionData> positions = GSON.fromJson(json,
                        new TypeToken<Map<String, PositionData>>(){}.getType());

                if (positions != null) {
                    playerPositions.put(playerId, positions);
                    MultiWorldPositions.LOGGER.info("Loaded {} positions for player {} (legacy)",
                            positions.size(), playerId);
                }
            }
        } catch (IOException e) {
            MultiWorldPositions.LOGGER.error("Failed to load player data for {}", playerId, e);
        }
    }

    public void saveAll(MinecraftServer server) {
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            String dimensionKey = player.getWorld().getRegistryKey().getValue().toString();

            if (!MultiWorldPositions.getConfig().isHubWorld(dimensionKey)) {
                savePosition(player);
            }

            savePlayerData(player.getUuid());
        }

        MultiWorldPositions.LOGGER.info("Saved positions for {} players",
                playerPositions.size());
    }

    public void clearPlayerPositions(UUID playerId) {
        playerPositions.remove(playerId);
        lastDefaultDimByPlayer.remove(playerId);
        lastGroupMemberByPlayer.remove(playerId);

        try {
            Path playerFile = STORAGE_PATH.resolve(playerId.toString() + ".json");
            Files.deleteIfExists(playerFile);
            MultiWorldPositions.LOGGER.info("Cleared all positions for player {}", playerId);
        } catch (IOException e) {
            MultiWorldPositions.LOGGER.error("Failed to delete player data file", e);
        }
    }

    // --- Admin/helpers ---
    public Map<String, PositionData> getAllPositions(UUID playerId) {
        return playerPositions.getOrDefault(playerId, new HashMap<>());
    }

    // Track last visited member dimension per group for each player
    public String getLastGroupMember(UUID playerId, String groupId) {
        Map<String, String> m = lastGroupMemberByPlayer.get(playerId);
        return (m != null) ? m.get(groupId) : null;
    }

    public void setLastGroupMember(UUID playerId, String groupId, String dimensionKey) {
        lastGroupMemberByPlayer
                .computeIfAbsent(playerId, k -> new HashMap<>())
                .put(groupId, dimensionKey);
    }

    public void setPosition(UUID playerId, String dimensionKey, PositionData pos) {
        playerPositions.computeIfAbsent(playerId, k -> new HashMap<>()).put(dimensionKey, pos);
        // If setting for a default world, also update lastDefaultDim
        if (MultiWorldPositions.getConfig().isDefaultWorld(dimensionKey)) {
            lastDefaultDimByPlayer.put(playerId, dimensionKey);
        }
        // Persist to disk
        savePlayerData(playerId);
    }

    public void clearPosition(UUID playerId, String dimensionKey) {
        Map<String, PositionData> map = playerPositions.get(playerId);
        if (map != null) {
            map.remove(dimensionKey);
        }
        savePlayerData(playerId);
    }

    // --- Transient last-known cache helpers ---
    public void updateLastKnown(ServerPlayerEntity player) {
        String dim = player.getWorld().getRegistryKey().getValue().toString();
        PositionData pos = new PositionData(player.getX(), player.getY(), player.getZ(), player.getYaw(), player.getPitch());
        net.minecraft.server.world.ServerWorld w = player.getServerWorld();
        net.minecraft.util.math.BlockPos feet = player.getBlockPos();
        boolean inNether = w.getBlockState(feet).isOf(net.minecraft.block.Blocks.NETHER_PORTAL)
                || w.getBlockState(feet.up()).isOf(net.minecraft.block.Blocks.NETHER_PORTAL);
        boolean inEnd = w.getBlockState(feet).isOf(net.minecraft.block.Blocks.END_PORTAL)
                || w.getBlockState(feet.down()).isOf(net.minecraft.block.Blocks.END_PORTAL);
        boolean pearlCd = player.getItemCooldownManager().isCoolingDown(new net.minecraft.item.ItemStack(net.minecraft.item.Items.ENDER_PEARL));
        lastKnownByPlayer.put(player.getUuid(), new LastKnown(dim, pos, inNether, inEnd, pearlCd));
        if (MultiWorldPositions.getConfig().debugMode) {
            // Keep debug light: don't spam every tick; print only occasionally if needed.
        }
    }

    public boolean wasInPortalCell(java.util.UUID playerId) {
        LastKnown lk = lastKnownByPlayer.get(playerId);
        return lk != null && lk.inNetherPortalCell;
    }

    public boolean wasInNetherPortalCell(java.util.UUID playerId) {
        LastKnown lk = lastKnownByPlayer.get(playerId);
        return lk != null && lk.inNetherPortalCell;
    }

    public boolean wasInEndPortalCell(java.util.UUID playerId) {
        LastKnown lk = lastKnownByPlayer.get(playerId);
        return lk != null && lk.inEndPortalCell;
    }

    public boolean hadEnderPearlCooldown(java.util.UUID playerId) {
        LastKnown lk = lastKnownByPlayer.get(playerId);
        return lk != null && lk.coolingEnderPearl;
    }

    public void saveCachedOriginIfMatches(ServerPlayerEntity player, String originKey) {
        ModConfig cfg = MultiWorldPositions.getConfig();
        if (cfg.isHubWorld(originKey)) {
            return; // never save hub worlds
        }
        LastKnown lk = lastKnownByPlayer.get(player.getUuid());
        if (lk != null && originKey.equals(lk.dimensionKey)) {
            // Persist the cached origin position under the origin dimension
            playerPositions.computeIfAbsent(player.getUuid(), k -> new HashMap<>()).put(originKey, lk.pos);
            // Update last default dimension if applicable
            if (cfg.isDefaultWorld(originKey)) {
                lastDefaultDimByPlayer.put(player.getUuid(), originKey);
            }
            if (cfg.debugMode) {
                MultiWorldPositions.LOGGER.debug("[MWP] Captured origin from cache for {} in {}: {}",
                        player.getName().getString(), originKey, lk.pos);
            }
            // Do not write to disk here; caller will handle savePlayerData at a sensible time
        }
    }
}
