# Changelog

## 0.3.0 (Phase 3)
- Added TeleportService abstraction with default LoggingTeleportService.
- Wired cross-dimension redirect flow to use TeleportService with validation and fallback.
- New config flags:
  - enableCrossDimRedirect (default: true)
  - failOpenOnTeleportError (default: true)
  - maxTeleportDistance (default: -1 disabled)
  - clampYToWorldBounds (default: true)
- Rapid-teleport debounce to prevent loops.
- Basic safety: Y clamping and optional max in-dimension teleport distance.
- Admin commands under /mwp: info, clear, set, reload-config (permission level 3+).
- Documentation updates (README, CONFIGURATION, INTEGRATION) and new COMMANDS doc.
- Version bumped to 0.3.0.
