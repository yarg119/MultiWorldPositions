# Configuration

The configuration file is generated on first server start at:

- config/multiworldpositions.json

This page explains each field in detail and provides examples.


## Fields

- hubWorldsExcluded (Set<String>)
  - A list of dimension keys that should be treated as hubs/lobbies.
  - Positions are not saved in these worlds and will not be restored when entering them.
  - Example identifiers: "multiverse:spawn", "myhub:hub_world".

- debugMode (boolean)
  - When true, the mod logs more information about save/restore operations and last default dimension updates.
  - Useful during setup or troubleshooting.

- defaultWorlds (Set<String>)
  - The set of worlds that are considered "default" (vanilla dimensions by default).
  - The mod tracks a per-player "last default dimension" and, upon entering any default world, can redirect to that last default dimension if different and a saved position exists.
  - Defaults: minecraft:overworld, minecraft:the_nether, minecraft:the_end

- enableCrossDimRedirect (boolean)
  - When true, entering any default world may redirect you to your last default world, if different and a saved position exists.
  - Default: true

- failOpenOnTeleportError (boolean)
  - If a cross-dimension redirect fails (or is unavailable), fall back to restoring position within the current dimension.
  - Default: true

- maxTeleportDistance (double)
  - Maximum distance allowed for in-dimension requestTeleport adjustments. Set to -1 to disable the check.
  - Default: -1

- clampYToWorldBounds (boolean)
  - Clamp saved Y to a conservative world height range when restoring (helps with legacy saves or version mismatches).
  - Default: true


## Default file contents

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


## Examples

### Example: Exclude a lobby world and enable debug logs
```json
{
  "hubWorldsExcluded": [
    "multiverse:spawn",
    "mycompany:lobby"
  ],
  "debugMode": true,
  "defaultWorlds": [
    "minecraft:overworld",
    "minecraft:the_nether",
    "minecraft:the_end"
  ]
}
```

### Example: Custom default worlds
If your server uses different identifiers for vanilla worlds (e.g., via a world management mod), list those here.
```json
{
  "hubWorldsExcluded": [],
  "debugMode": false,
  "defaultWorlds": [
    "server:overworld_main",
    "server:nether_main",
    "server:end_main"
  ]
}
```


## Tips
- Dimension keys must match the server’s RegistryKey values for worlds. Use debugMode=true to print keys in logs and verify.
- Changing defaultWorlds does not delete existing saved positions. Old positions remain in config/worldpositions/<uuid>.json.
- To reset a player’s saved positions and last default dimension, you can delete their file in config/worldpositions or use a future admin command once available.
