package com.yarg.mwpositions;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class ModConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = Path.of("config", "multiworldpositions.json");

    public Set<String> hubWorldsExcluded = new HashSet<>();
    public boolean debugMode = false;

    // New: default worlds group (vanilla dimensions)
    public Set<String> defaultWorlds = new HashSet<>();

    // Feature toggles and safety options
    public boolean enableCrossDimRedirect = true;
    public boolean failOpenOnTeleportError = true;
    /**
     * Disable all custom portal linking/creation logic when false.
     */
    public boolean enablePortals = false;
    /**
     * Maximum distance for in-dimension requestTeleport adjustments. -1 disables the check.
     */
    public double maxTeleportDistance = -1.0;
    /**
     * Clamp saved Y coordinate to the target world's build height when restoring.
     */
    public boolean clampYToWorldBounds = true;

    // New: world groups configuration
    public List<WorldGroup> worldGroups = new ArrayList<>();

    // Inventory fallback policies (Option 2)
    public boolean inventoryProfileForDefaultWorlds = true; // treat vanilla defaults as logical group "__default"
    public boolean inventoryProfileForUngrouped = false;    // optional broader fallback group "__ungrouped"

    // Portal detection fallback
    public boolean fallbackDetectFrames = true; // allow linking even if portal blocks can't be placed

    // Portal frame size and behavior
    public int portalMinInnerWidth = 2;
    public int portalMinInnerHeight = 3;
    public int portalMaxInnerWidth = 21;   // outer 23
    public int portalMaxInnerHeight = 21;
    public boolean allowOversizeFrames = true;
    public boolean requireCompleteFrame = true;
    public int portalWarmupTicks = 60;     // ~3 seconds default
    public boolean createReturnPortal = true;
    public int returnPortalSearchRadius = 64;
    public boolean alignToNearestReturnPortal = true;

    // End arrival/platform behavior
    public boolean endCreateArrivalPlatform = true;
    public int endPlatformRadius = 3; // small square radius
    public String endPlatformBlock = "minecraft:end_stone"; // future-proof, default to END_STONE
    public boolean endSpawnDragonOnArrival = true;
    public int endMainIslandSearchRadius = 128;

    // Item-spawned portal feature
    public boolean specialPortalEnabled = true;
    public String specialPortalItemId = "minecraft:blaze_rod"; // trigger item
    public int specialPortalCooldownTicks = 100; // per-player spam control (~5s)
    public int specialPortalClearanceRadius = 3; // open area radius check
    public boolean specialPortalEnsureReturn = true; // precreate/snap return portal in target

    // In-dimension restore safety
    public boolean restoreUseSafeLocation = true; // use SafeLocationFinder on in-dimension restores
    public int restoreSafeSearchRadius = 16; // reserved for future use

    // Placement/mob clearance
    public boolean killBlockingMobs = true;          // allow removing mobs in the way
    public double killBlockingRadius = 0.75;         // half-extent for the clearance box

    public ModConfig() {
        hubWorldsExcluded.add("multiverse:spawn");
        // Prepopulate vanilla default dimensions
        defaultWorlds.add("minecraft:overworld");
        defaultWorlds.add("minecraft:the_nether");
        defaultWorlds.add("minecraft:the_end");
    }

    public boolean isHubWorld(String dimensionKey) {
        return hubWorldsExcluded.contains(dimensionKey);
    }

    // New: check if dimension belongs to default group
    public boolean isDefaultWorld(String dimensionKey) {
        return defaultWorlds.contains(dimensionKey);
    }

    // --- World groups helpers ---
    public WorldGroup findGroupByMember(String dimensionKey) {
        if (dimensionKey == null) return null;
        for (WorldGroup g : worldGroups) {
            if (g == null) continue;
            if (dimensionKey.equals(g.overworld) || dimensionKey.equals(g.nether) || dimensionKey.equals(g.end)) {
                return g;
            }
        }
        return null;
    }

    public String getGroupIdForWorld(String dimensionKey) {
        WorldGroup g = findGroupByMember(dimensionKey);
        return g != null ? g.id : null;
    }

    /**
     * Resolve an inventory group id for a world, applying fallback policies when not in a declared group.
     * Returns one of:
     *  - a declared group id
     *  - "__default" when inventoryProfileForDefaultWorlds is true and the world is a default world
     *  - "__ungrouped" when inventoryProfileForUngrouped is true and not default/grouped
     *  - null if no inventory grouping applies
     */
    public String resolveInventoryGroupId(String dimensionKey) {
        String gid = getGroupIdForWorld(dimensionKey);
        if (gid != null) return gid;
        if (inventoryProfileForDefaultWorlds && isDefaultWorld(dimensionKey)) return "__default";
        if (inventoryProfileForUngrouped) return "__ungrouped";
        return null;
    }

    public boolean isGroupOverworld(WorldGroup g, String dim) {
        return g != null && dim != null && dim.equals(g.overworld);
    }

    public boolean isGroupNether(WorldGroup g, String dim) {
        return g != null && dim != null && dim.equals(g.nether);
    }

    public boolean isGroupEnd(WorldGroup g, String dim) {
        return g != null && dim != null && dim.equals(g.end);
    }

    public String nextForPortal(WorldGroup g, String fromDim, PortalKind kind) {
        if (g == null || fromDim == null || kind == null) return null;
        switch (kind) {
            case NETHER:
                if (fromDim.equals(g.overworld)) return g.nether;
                if (fromDim.equals(g.nether)) return g.overworld;
                return null;
            case END:
                if (fromDim.equals(g.overworld)) return g.end;
                if (fromDim.equals(g.end)) return g.overworld;
                return null;
            default:
                return null;
        }
    }

    public static ModConfig load() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());

            if (Files.exists(CONFIG_PATH)) {
                String json = Files.readString(CONFIG_PATH);
                ModConfig config = GSON.fromJson(json, ModConfig.class);
                MultiWorldPositions.LOGGER.info("Loaded config from file");
                return config;
            } else {
                ModConfig config = new ModConfig();
                config.save();
                MultiWorldPositions.LOGGER.info("Created default config file");
                return config;
            }
        } catch (IOException e) {
            MultiWorldPositions.LOGGER.error("Failed to load config, using defaults", e);
            return new ModConfig();
        }
    }

    public void save() {
        try {
            String json = GSON.toJson(this);
            Files.writeString(CONFIG_PATH, json);
        } catch (IOException e) {
            MultiWorldPositions.LOGGER.error("Failed to save config", e);
        }
    }
}