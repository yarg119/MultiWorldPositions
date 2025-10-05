# Commands

All commands require permission level 3+ (server operators by default), unless noted.

Root: /mwp

- /mwp info <player>
  - Shows lastDefaultDimension, saved dimensions count, and a short list of saved positions per dimension.

- /mwp clear <player>
  - Clears all saved positions and lastDefaultDimension for the player.

- /mwp clear <player> <dimensionKey>
  - Clears the saved position only for the specified dimension key.

- /mwp set <player> <dimensionKey> <x> <y> <z> [yaw] [pitch]
  - Manually sets a saved position for the player and dimension.

- /mwp reload-config
  - Reloads config/multiworldpositions.json at runtime.

Additional commands

- /mwp-tp <player> <dimension>
  - Teleports the specified player to the target dimension using the mod’s TeleportService.
  - Use this instead of selector chains like `execute as @p run mw tp ...` to avoid multi-player targeting races.

- /survival (permission level 0)
  - Applies to the executor only. Teleports you to minecraft:overworld and switches your game mode to SURVIVAL.
  - Designed to be safe in multiplayer: it never targets @p or any selector.

- Per-group commands (permission level 0)
  - For every configured world group, the mod registers a command named exactly after the group id.
  - Examples: `/creative`, `/survival` (if those are your group ids)
  - Behavior: teleports the executor to the group’s primary world (overworld if set, otherwise nether or end) and may set gamemode inferred from the id (e.g., creative/survival).

 Notes:
 - Dimension keys must match the server’s registry identifiers (e.g., minecraft:the_nether).
 - Use debugMode=true in the config to see detailed logs while using these commands.
 - For multiplayer safety, prefer explicit player arguments or executor-only commands over `@p` in chained command blocks.
