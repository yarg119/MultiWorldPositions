package com.yarg.mwpositions;

import net.minecraft.inventory.Inventories;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.collection.DefaultedList;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * 1.21.9 version: Stores per-group inventory snapshots per player using raw NBT files.
 * Updated to use the new 1.21.9 NBT API with RegistryWrapper.WrapperLookup.
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

            // Get the registry wrapper from the player's world
            RegistryWrapper.WrapperLookup registries = player.getEntityWorld().getRegistryManager();

            // Copy player inventory to a list
            DefaultedList<ItemStack> inventory = DefaultedList.ofSize(player.getInventory().size(), ItemStack.EMPTY);
            for (int i = 0; i < player.getInventory().size(); i++) {
                inventory.set(i, player.getInventory().getStack(i));
            }

            // Manually write inventory to NBT (Inventories helper API changed in 1.21.9)
            net.minecraft.nbt.NbtList itemsList = new net.minecraft.nbt.NbtList();
            for (int i = 0; i < inventory.size(); i++) {
                ItemStack stack = inventory.get(i);
                if (!stack.isEmpty()) {
                    NbtCompound itemNbt = new NbtCompound();
                    itemNbt.putByte("Slot", (byte) i);
                    NbtCompound stackNbt = (NbtCompound) ItemStack.CODEC.encode(stack, registries.getOps(net.minecraft.nbt.NbtOps.INSTANCE), new NbtCompound()).getOrThrow();
                    itemNbt.put("Item", stackNbt);
                    itemsList.add(itemNbt);
                }
            }
            root.put("Items", itemsList);

            // XP level (keep simple int field)
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

            // Get the registry wrapper from the player's world
            RegistryWrapper.WrapperLookup registries = player.getEntityWorld().getRegistryManager();

            // Create a list to hold loaded items
            DefaultedList<ItemStack> inventory = DefaultedList.ofSize(player.getInventory().size(), ItemStack.EMPTY);

            // Manually read inventory from NBT (Inventories helper API changed in 1.21.9)
            net.minecraft.nbt.NbtList itemsList = root.getListOrEmpty("Items");
            for (int i = 0; i < itemsList.size(); i++) {
                NbtCompound itemNbt = itemsList.getCompound(i).orElse(new NbtCompound());
                int slot = itemNbt.getByte("Slot", (byte) 0) & 255;
                if (slot >= 0 && slot < inventory.size()) {
                    NbtCompound stackNbt = itemNbt.getCompound("Item").orElse(new NbtCompound());
                    ItemStack stack = ItemStack.CODEC.parse(registries.getOps(net.minecraft.nbt.NbtOps.INSTANCE), stackNbt).result().orElse(ItemStack.EMPTY);
                    inventory.set(slot, stack);
                }
            }

            // Clear current inventory before applying group snapshot
            player.getInventory().clear();

            // Apply items to player inventory
            for (int i = 0; i < inventory.size(); i++) {
                player.getInventory().setStack(i, inventory.get(i));
            }

            player.getInventory().markDirty();

            // Restore XP level using the new API with default value
            int lvl = root.getInt("XpLevel", 0);
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
            String dim = p.getEntityWorld().getRegistryKey().getValue().toString();
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
