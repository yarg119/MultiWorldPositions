package com.yarg.mwpositions;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Stores per-group inventory snapshots per player using raw NBT files for reliability.
 * Snapshot includes: Player inventory (NbtList) and XP level.
 */
public class InventoryStorage {
    private static final Path STORAGE_DIR = Path.of("config", "worldinventories");

    public InventoryStorage() {
        try {
            Files.createDirectories(STORAGE_DIR);
        } catch (IOException e) {
            MultiWorldPositions.LOGGER.error("[MWP] Failed to create inventory storage dir", e);
        }
    }

    private Path fileFor(UUID playerId, String groupId) {
        String safe = groupId.replace(':', '_');
        return STORAGE_DIR.resolve(playerId.toString() + "_" + safe + ".nbt");
    }

    public void saveForGroup(ServerPlayerEntity player, String groupId) {
        if (groupId == null) return;
        try {
            NbtCompound root = new NbtCompound();
            NbtList invList = new NbtList();
            player.getInventory().writeNbt(invList);
            root.put("Inventory", invList);
            root.putInt("XpLevel", player.experienceLevel);
            NbtIo.write(root, fileFor(player.getUuid(), groupId));
        } catch (Exception e) {
            MultiWorldPositions.LOGGER.error("[MWP] Failed to write inventory snapshot for {} [{}]", player.getName().getString(), groupId, e);
        }
    }

    public void loadForGroup(ServerPlayerEntity player, String groupId) {
        if (groupId == null) return;
        try {
            Path path = fileFor(player.getUuid(), groupId);
            if (!Files.exists(path)) return;
            NbtCompound root = NbtIo.read(path);
            NbtList invListRead = root.getList("Inventory", NbtElement.COMPOUND_TYPE);
            // Clear current inventory before applying group snapshot to avoid merges
            player.getInventory().clear();
            player.getInventory().readNbt(invListRead);
            int lvl = root.getInt("XpLevel");
            player.experienceLevel = 0;
            if (lvl > 0) player.addExperienceLevels(lvl);
            player.currentScreenHandler.sendContentUpdates();
        } catch (Exception e) {
            MultiWorldPositions.LOGGER.error("[MWP] Failed to read inventory snapshot for {} [{}]", player.getName().getString(), groupId, e);
        }
    }

    public void saveAll(MinecraftServer server) {
        ModConfig cfg = MultiWorldPositions.getConfig();
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            String dim = p.getWorld().getRegistryKey().getValue().toString();
            String gid = cfg.getGroupIdForWorld(dim);
            if (gid != null) {
                saveForGroup(p, gid);
            }
        }
    }

    public void loadPlayer(UUID playerId) {
        // NBT files are read on demand in loadForGroup; nothing needed here.
    }
}
