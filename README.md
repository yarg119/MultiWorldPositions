# MultiWorld Positions (Fabric)

Tracks and restores player positions across worlds/dimensions on a dedicated server. It saves a player’s last location per world, restores on return, and adds special behavior for vanilla “default worlds” (Overworld, Nether, End) while allowing you to exclude hub/lobby worlds from saving.

Status: server-side only. Target: Minecraft 1.21.4 + Fabric API.


## Features
- Per-world position saving on teleport, respawn, disconnect, and shutdown.
- Exclusion list for hub/lobby worlds so they don’t pollute saved positions.
- Default worlds group: treats Overworld, Nether, End as one connected set with last-known default dimension tracking.
  - When a player enters any default world, the mod can redirect them to the last default dimension they were in and restore that dimension’s saved coordinates.
- JSON config file is auto-generated; easy to edit.
- Player data saved per UUID in JSON for portability and safe backups.
- Debug logging toggle for deeper insights during setup/testing.


## How it works (current behavior)
- The mod listens for dimension changes via Fabric’s ServerPlayerEvents.AFTER_RESPAWN and also saves on disconnect and server stopping.
- When a player changes dimension:
  1) Save the old dimension’s position unless it’s listed as a hub world.
  2) If the new dimension is a default world (one of Overworld/Nether/End by default):
     - Check the player’s last default dimension.
     - If the last default dimension is different and there’s a saved position for it, the mod intends to redirect the player there (cross-dimension teleport) and then restore that position. In this template, the actual cross-dimension teleport is a stub that only logs the intent (see Integration notes below).
     - Otherwise, it proceeds to restore position within the current dimension (if saved).
  3) If the new dimension is not a hub world, restore the saved position within that dimension if available.


## Configuration quick start
A config file is created at first run:
- config/multiworldpositions.json

Default contents (subject to change as features evolve):

```json
{
  "hubWorldsExcluded": [
    "multiverse:spawn"
  ],
  "debugMode": false,
  "defaultWorlds": [
    "minecraft:overworld",
    "minecraft:the_nether",
    "minecraft:the_end"
  ]
}
```

- hubWorldsExcluded: Worlds here will never be saved/restored.
- debugMode: Write extra logs for troubleshooting.
- defaultWorlds: The set of worlds that are treated as the “default” connected dimensions. You can adjust if your server uses different keys.

See docs/CONFIGURATION.md for details and examples.


## Player data storage
Saved at: config/worldpositions/<player-uuid>.json

Schema (new format):
```json
{
  "positions": {
    "minecraft:overworld": {"x": 0.0, "y": 64.0, "z": 0.0, "yaw": 0.0, "pitch": 0.0, "timestamp": 1700000000000},
    "minecraft:the_nether": {"x": 10.0, "y": 70.0, "z": 10.0, "yaw": 90.0, "pitch": 0.0, "timestamp": 1700000000001}
  },
  "lastDefaultDimension": "minecraft:the_nether"
}
```
- Positions map is per dimension key.
- lastDefaultDimension is the last default world the player visited (one of defaultWorlds).
- Legacy files (a plain map of dimension -> position) are still read for backward compatibility.


## Installation
- Server: Put the built mod jar into the server’s mods folder along with Fabric Loader and Fabric API.
- Client: Not required.
- Start the server once to generate the configuration file.


## Building from source
Requirements:
- JDK 21+
- Gradle (wrapper included)

Commands:
- Windows: gradlew.bat build
- macOS/Linux: ./gradlew build

The resulting jar will be in build/libs.


## Logging and debug mode
Set debugMode to true in config/multiworldpositions.json to enable extra logging:
- Saves and loads per player/dimension
- Skipped saves/restores for hub worlds
- Updates to lastDefaultDimension

This helps verify that your dimension keys and exclusions are set correctly.


## Compatibility notes
- Minecraft: ~1.21.4
- Requires: Fabric Loader >= 0.16.0, Fabric API
- Environment: server


## Integration: cross-dimension teleport
Right now, cross-dimension teleport is a stub that logs the intent only. This keeps the template lightweight and mapping-agnostic. If you want automatic redirection between default worlds, wire the call in DimensionChangeListener.crossDimTeleport to your environment:
- Fabric API: FabricDimensions.teleport(...)
- Or use your server’s teleport utilities/commands.

See docs/INTEGRATION.md for code pointers.


## Known limitations / roadmap
- Cross-dimension teleport is not executed by default (stub only). Integrate for auto-redirect.
- No built-in commands yet; server commands like /survival or /creative are out of scope and can be customized separately.
- The mod assumes dimension keys are stable strings (e.g., minecraft:the_nether). Adjust config if your server uses different keys.

Future tasks can expand:
- Pluggable teleport integration
- Admin commands to manage per-player saved positions
- Permissions integration and temporary disable per player


## FAQ
- Q: Does this work on clients? A: It’s server-side only.
- Q: Can I add more default worlds? A: Yes, add identifiers in defaultWorlds.
- Q: How do I exclude my lobby world? A: Add its dimension key to hubWorldsExcluded.
- Q: Where are my positions saved? A: config/worldpositions/<uuid>.json per player.


## License
MIT (see LICENSE).


## New in 0.3.0 (Phase 3)
- Pluggable TeleportService with default logging-only implementation.
- Config flags:
  - enableCrossDimRedirect (default: true)
  - failOpenOnTeleportError (default: true)
  - maxTeleportDistance (default: -1 to disable)
  - clampYToWorldBounds (default: true)
- Rapid-teleport debounce to avoid loops.
- Admin commands under /mwp (permission level 3+): info, clear, set, reload-config.

See docs/COMMANDS.md and docs/INTEGRATION.md for usage.
