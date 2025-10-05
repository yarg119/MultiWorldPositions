# Integration: Cross-dimension teleport

The mod now uses a pluggable TeleportService abstraction. By default, `LoggingTeleportService` only logs the intent, keeping the base jar mapping-agnostic. You can swap in a real implementation for your environment (e.g., FabricDimensions) without changing core logic.

If you want automatic redirection between default worlds (Overworld/Nether/End), provide a TeleportService that performs the cross-dimension move and let the listener set the precise coordinates after the move.


## Where to integrate
File: src/main/java/com/yarg/mwpositions/DimensionChangeListener.java
Method: `private static void crossDimTeleport(ServerPlayerEntity player, String targetDimKey, PositionData pos)`

The method is called when:
- The player enters a default world and their last default world is different, and
- A saved position for that last default world exists.


## Option A: Fabric API (FabricDimensions)
If you are comfortable depending on Fabric API’s dimensions API for your distribution, you can do something similar to:

```java
import net.fabricmc.fabric.api.dimension.v1.FabricDimensions;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;

private static void crossDimTeleport(ServerPlayerEntity player, String targetDimKey, PositionData pos) {
    Identifier id = Identifier.of(targetDimKey);
    RegistryKey<ServerWorld> key = RegistryKey.of(RegistryKeys.WORLD, id);
    ServerWorld targetWorld = player.getServer().getWorld(key);
    if (targetWorld == null) {
        MultiWorldPositions.LOGGER.warn("Target world not found for {}", targetDimKey);
        return;
    }

    // Teleport across dimensions, then set precise position/rotation
    FabricDimensions.teleport(player, targetWorld, (entity, world, yaw, pitch) -> {
        return entity;
    });

    player.networkHandler.requestTeleport(pos.x, pos.y, pos.z, pos.yaw, pos.pitch);
}
```

Note: Depending on mapping/version, the API may vary. Consult the Fabric API docs for your exact environment.


## Option B: Server-specific utilities
Some server environments or companion mods expose their own teleport utilities or commands (e.g., via a plugin or admin mod). In that case, call your utility here and then update the position with `requestTeleport` once the player is in the target world.


## Safety tips
- Always null-check that the target world exists.
- Consider spawn-safety checks if you expect saved positions near hazards.
- Preserve player yaw/pitch when restoring.
- Keep the `lastDefaultDimension` logic in sync if you change the flow; currently it’s updated on saves in default worlds.


## Logging
The stub logs an intent like:
```
[MWP] Intended cross-dimension redirect: <player> -> <namespace:path> at (x, y, z, yaw, pitch)
```
Use `debugMode=true` in the config for more granular logs during development.
