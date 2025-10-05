package com.yarg.mwpositions;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.command.argument.GameProfileArgumentType;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;

import java.util.Map;
import java.util.UUID;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public class MwpCommands {
    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(
                literal("mwp")
                        .requires(src -> src.hasPermissionLevel(3))
                        .then(literal("info")
                                .then(argument("player", GameProfileArgumentType.gameProfile())
                                        .executes(ctx -> {
                                            var profiles = GameProfileArgumentType.getProfileArgument(ctx, "player");
                                            var profile = profiles.iterator().next();
                                            UUID uuid = profile.getId();
                                            if (uuid == null) {
                                                ctx.getSource().sendError(Text.of("Player UUID not found"));
                                                return 0;
                                            }
                                            PositionStorage storage = MultiWorldPositions.getPositionStorage();
                                            Map<String, PositionData> positions = storage.getAllPositions(uuid);
                                            String lastDefault = storage.getLastDefaultDimension(uuid);
                                            int count = positions != null ? positions.size() : 0;
                                            ctx.getSource().sendFeedback(() -> Text.of("lastDefaultDimension=" + lastDefault + ", savedDimensions=" + count), false);
                                            if (positions != null) {
                                                for (Map.Entry<String, PositionData> e : positions.entrySet()) {
                                                    ctx.getSource().sendFeedback(() -> Text.of(e.getKey() + " -> " + e.getValue()), false);
                                                }
                                            }
                                            return 1;
                                        })
                                )
                        )
                        .then(literal("clear")
                                .then(argument("player", GameProfileArgumentType.gameProfile())
                                        .executes(ctx -> {
                                            var profiles = GameProfileArgumentType.getProfileArgument(ctx, "player");
                                            var profile = profiles.iterator().next();
                                            UUID uuid = profile.getId();
                                            if (uuid == null) {
                                                ctx.getSource().sendError(Text.of("Player UUID not found"));
                                                return 0;
                                            }
                                            MultiWorldPositions.getPositionStorage().clearPlayerPositions(uuid);
                                            ctx.getSource().sendFeedback(() -> Text.of("Cleared all saved positions for " + profile.getName()), true);
                                            return 1;
                                        })
                                        .then(argument("dimensionKey", StringArgumentType.string())
                                                .executes(ctx -> {
                                                    var profiles = GameProfileArgumentType.getProfileArgument(ctx, "player");
                                                    var profile = profiles.iterator().next();
                                                    UUID uuid = profile.getId();
                                                    String dim = StringArgumentType.getString(ctx, "dimensionKey");
                                                    if (uuid == null) {
                                                        ctx.getSource().sendError(Text.of("Player UUID not found"));
                                                        return 0;
                                                    }
                                                    MultiWorldPositions.getPositionStorage().clearPosition(uuid, dim);
                                                    ctx.getSource().sendFeedback(() -> Text.of("Cleared saved position for " + profile.getName() + " in " + dim), true);
                                                    return 1;
                                                })
                                        )
                                )
                        )
                        .then(literal("set")
                                .then(argument("player", GameProfileArgumentType.gameProfile())
                                        .then(argument("dimensionKey", StringArgumentType.string())
                                                .then(argument("x", DoubleArgumentType.doubleArg())
                                                        .then(argument("y", DoubleArgumentType.doubleArg())
                                                                .then(argument("z", DoubleArgumentType.doubleArg())
                                                                        .executes(ctx -> {
                                                                            var profiles = GameProfileArgumentType.getProfileArgument(ctx, "player");
                                                                            var profile = profiles.iterator().next();
                                                                            UUID uuid = profile.getId();
                                                                            String dim = StringArgumentType.getString(ctx, "dimensionKey");
                                                                            double x = DoubleArgumentType.getDouble(ctx, "x");
                                                                            double y = DoubleArgumentType.getDouble(ctx, "y");
                                                                            double z = DoubleArgumentType.getDouble(ctx, "z");
                                                                            PositionData pos = new PositionData(x, y, z, 0f, 0f);
                                                                            MultiWorldPositions.getPositionStorage().setPosition(uuid, dim, pos);
                                                                            ctx.getSource().sendFeedback(() -> Text.of("Set saved position for " + profile.getName() + " in " + dim + " to " + pos), true);
                                                                            return 1;
                                                                        })
                                                                )
                                                        )
                                                )
                                        )
                                )
                        )
                        .then(literal("reload-config")
                                .executes(ctx -> {
                                    MultiWorldPositions.reloadConfig();
                                    ctx.getSource().sendFeedback(() -> Text.of("Reloaded multiworldpositions.json"), true);
                                    return 1;
                                })
                        )
        );

        // Admin: explicit player + dimension teleport, avoiding @p selector ambiguity
        dispatcher.register(
                literal("mwp-tp")
                        .requires(src -> src.hasPermissionLevel(3))
                        .then(argument("player", GameProfileArgumentType.gameProfile())
                                .then(argument("dimension", StringArgumentType.string())
                                        .executes(ctx -> {
                                            var profiles = GameProfileArgumentType.getProfileArgument(ctx, "player");
                                            var profile = profiles.iterator().next();
                                            var player = ctx.getSource().getServer().getPlayerManager().getPlayer(profile.getId());
                                            String dim = StringArgumentType.getString(ctx, "dimension");
                                            if (player == null) {
                                                ctx.getSource().sendError(Text.of("Player not found"));
                                                return 0;
                                            }
                                            boolean ok = MultiWorldPositions.getTeleportService().teleport(player, dim);
                                            return ok ? 1 : 0;
                                        })
                                )
                        )
        );

        // Simple: applies to the executor only, avoids @p races
        dispatcher.register(
                literal("survival")
                        .requires(src -> src.hasPermissionLevel(0))
                        .executes(ctx -> {
                            var player = ctx.getSource().getPlayer();
                            if (player == null) return 0;

                            // Try to find a configured group with id "survival"
                            WorldGroup survivalGroup = null;
                            for (WorldGroup g : MultiWorldPositions.getConfig().worldGroups) {
                                if (g != null && "survival".equalsIgnoreCase(g.id)) { survivalGroup = g; break; }
                            }
                            String destDim;
                            if (survivalGroup != null) {
                                // Prefer last visited member within the survival group
                                String last = MultiWorldPositions.getPositionStorage().getLastGroupMember(player.getUuid(), survivalGroup.id);
                                if (last != null && !MultiWorldPositions.getConfig().isHubWorld(last)) {
                                    destDim = last;
                                } else if (survivalGroup.overworld != null && !survivalGroup.overworld.isBlank()) {
                                    destDim = survivalGroup.overworld;
                                } else if (survivalGroup.nether != null && !survivalGroup.nether.isBlank()) {
                                    destDim = survivalGroup.nether;
                                } else if (survivalGroup.end != null && !survivalGroup.end.isBlank()) {
                                    destDim = survivalGroup.end;
                                } else {
                                    destDim = "minecraft:overworld";
                                }
                            } else {
                                destDim = "minecraft:overworld";
                            }

                            boolean ok = MultiWorldPositions.getTeleportService().teleport(player, destDim);
                            if (ok) {
                                player.changeGameMode(net.minecraft.world.GameMode.SURVIVAL);
                                // Placement handled by DimensionChangeListener
                            }
                            return ok ? 1 : 0;
                        })
        );

        // Dynamically register one executor-only command per world group, named by the group id
        for (WorldGroup g : MultiWorldPositions.getConfig().worldGroups) {
            if (g == null || g.id == null || g.id.isBlank()) continue;
            String cmdName = g.id; // must be a valid literal

            // Choose destination world (prefer group overworld)
            String destDim = g.overworld != null && !g.overworld.isBlank() ? g.overworld
                    : (g.nether != null && !g.nether.isBlank() ? g.nether
                    : (g.end != null && !g.end.isBlank() ? g.end : null));
            if (destDim == null) continue; // nothing to teleport to

            // Optional gamemode inference from id
            java.util.function.Consumer<net.minecraft.server.network.ServerPlayerEntity> gmSetter = p -> {};
            String idLower = cmdName.toLowerCase(java.util.Locale.ROOT);
            if (idLower.contains("creative")) gmSetter = p -> p.changeGameMode(net.minecraft.world.GameMode.CREATIVE);
            else if (idLower.contains("survival")) gmSetter = p -> p.changeGameMode(net.minecraft.world.GameMode.SURVIVAL);
            else if (idLower.contains("hub")) gmSetter = p -> p.changeGameMode(net.minecraft.world.GameMode.ADVENTURE);

            // Snapshot for lambda capture
            final java.util.function.Consumer<net.minecraft.server.network.ServerPlayerEntity> gmFinal = gmSetter;
            final String destFinal = destDim;
            final WorldGroup gFinal = g;
            final String cmdFinal = cmdName;

            dispatcher.register(
                    literal(cmdName)
                            .requires(src -> src.hasPermissionLevel(0)) // executor-only, anyone can use; adjust if needed
                            .executes(ctx -> {
                                var player = ctx.getSource().getPlayer();
                                if (player == null) return 0;
                                // Prefer last visited member of this group if recorded; otherwise fallback to primary dest
                                String dimCandidate = MultiWorldPositions.getPositionStorage().getLastGroupMember(player.getUuid(), gFinal.id);
                                if (dimCandidate == null || MultiWorldPositions.getConfig().isHubWorld(dimCandidate)) {
                                    dimCandidate = destFinal;
                                }

                                boolean ok = MultiWorldPositions.getTeleportService().teleport(player, dimCandidate);
                                if (ok) {
                                    gmFinal.accept(player);

                                    // For hub specifically, place at configured spawn from config (or world spawn fallback)
                                    if ("hub".equalsIgnoreCase(cmdFinal)) {
                                        // Explicitly set Adventure mode for hub command
                                        player.changeGameMode(net.minecraft.world.GameMode.ADVENTURE);
                                        PositionData target;
                                        if (gFinal.spawnX != null && gFinal.spawnY != null && gFinal.spawnZ != null) {
                                            double tx = gFinal.spawnX;
                                            double tz = gFinal.spawnZ;
                                            if (Math.floor(tx) == tx) tx += 0.5;
                                            if (Math.floor(tz) == tz) tz += 0.5;
                                            target = new PositionData(
                                                    tx,
                                                    gFinal.spawnY,
                                                    tz,
                                                    gFinal.spawnYaw != null ? gFinal.spawnYaw : player.getYaw(),
                                                    gFinal.spawnPitch != null ? gFinal.spawnPitch : player.getPitch()
                                            );
                                        } else {
                                            // Fall back to destination world's spawn
                                            net.minecraft.util.Identifier id = net.minecraft.util.Identifier.of(dimCandidate);
                                            net.minecraft.registry.RegistryKey<net.minecraft.world.World> key = net.minecraft.registry.RegistryKey.of(net.minecraft.registry.RegistryKeys.WORLD, id);
                                            net.minecraft.server.world.ServerWorld tw = player.getServer().getWorld(key);
                                            if (tw != null) {
                                                net.minecraft.util.math.BlockPos sp = tw.getSpawnPos();
                                                target = new PositionData(sp.getX() + 0.5, sp.getY(), sp.getZ() + 0.5, player.getYaw(), player.getPitch());
                                            } else {
                                                // As a last resort, keep current post-teleport position
                                                target = new PositionData(player.getX(), player.getY(), player.getZ(), player.getYaw(), player.getPitch());
                                            }
                                        }
                                        // Persist the configured hub spawn as this player's saved hub position
                                        MultiWorldPositions.getPositionStorage().setPosition(player.getUuid(), dimCandidate, target);
                                        final PositionData hubTarget = target;
                                        final String hubDim = dimCandidate;
                                        // Defer placement to ensure we are fully in the destination world
                                        player.getServer().execute(() -> {
                                            TeleportPlacement.placeExactlyOrNearby(player, hubDim, hubTarget);
                                        });
                                    }
                                    // For non-hub groups, placement is handled by DimensionChangeListener on world change
                                }
                                return ok ? 1 : 0;
                            })
            );
        }
    }
}
