package com.yarg.mwpositions;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.server.command.ServerCommandSource;

import static net.minecraft.server.command.CommandManager.literal;

/**
 * 1.21.9 overlay: minimal commands (no GameProfileArgumentType) to avoid mapping differences.
 * Provides /survival and per-group commands only.
 */
public class MwpCommands {
    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        // /survival (executor only)
        dispatcher.register(
                literal("survival")
                        .requires(src -> src.hasPermissionLevel(0))
                        .executes(ctx -> {
                            var player = ctx.getSource().getPlayer();
                            if (player == null) return 0;

                            // Use survival group if present; else overworld
                            WorldGroup survivalGroup = null;
                            for (WorldGroup g : MultiWorldPositions.getConfig().worldGroups) {
                                if (g != null && "survival".equalsIgnoreCase(g.id)) { survivalGroup = g; break; }
                            }
                            String destDim;
                            if (survivalGroup != null) {
                                String last = MultiWorldPositions.getPositionStorage().getLastGroupMember(player.getUuid(), survivalGroup.id);
                                if (last != null && !MultiWorldPositions.getConfig().isHubWorld(last)) destDim = last;
                                else if (survivalGroup.overworld != null && !survivalGroup.overworld.isBlank()) destDim = survivalGroup.overworld;
                                else if (survivalGroup.nether != null && !survivalGroup.nether.isBlank()) destDim = survivalGroup.nether;
                                else if (survivalGroup.end != null && !survivalGroup.end.isBlank()) destDim = survivalGroup.end;
                                else destDim = "minecraft:overworld";
                            } else {
                                destDim = "minecraft:overworld";
                            }

                            boolean ok = MultiWorldPositions.getTeleportService().teleport(player, destDim);
                            if (ok) {
                                player.changeGameMode(net.minecraft.world.GameMode.SURVIVAL);
                            }
                            return ok ? 1 : 0;
                        })
        );

        // Per-group commands (executor only)
        for (WorldGroup g : MultiWorldPositions.getConfig().worldGroups) {
            if (g == null || g.id == null || g.id.isBlank()) continue;
            String cmdName = g.id;
            String destDim = g.overworld != null && !g.overworld.isBlank() ? g.overworld
                    : (g.nether != null && !g.nether.isBlank() ? g.nether
                    : (g.end != null && !g.end.isBlank() ? g.end : null));
            if (destDim == null) continue;

            final String destFinal = destDim;
            final WorldGroup gFinal = g;

            dispatcher.register(
                    literal(cmdName)
                            .requires(src -> src.hasPermissionLevel(0))
                            .executes(ctx -> {
                                var player = ctx.getSource().getPlayer();
                                if (player == null) return 0;
                                String dimCandidate = MultiWorldPositions.getPositionStorage().getLastGroupMember(player.getUuid(), gFinal.id);
                                if (dimCandidate == null || MultiWorldPositions.getConfig().isHubWorld(dimCandidate)) dimCandidate = destFinal;

                                boolean ok = MultiWorldPositions.getTeleportService().teleport(player, dimCandidate);
                                if (ok) {
                                    // Gamemode inference
                                    String idLower = cmdName.toLowerCase(java.util.Locale.ROOT);
                                    if (idLower.contains("creative")) player.changeGameMode(net.minecraft.world.GameMode.CREATIVE);
                                    else if (idLower.contains("survival")) player.changeGameMode(net.minecraft.world.GameMode.SURVIVAL);
                                    else if (idLower.contains("hub")) player.changeGameMode(net.minecraft.world.GameMode.ADVENTURE);

                                    if ("hub".equalsIgnoreCase(cmdName)) {
                                        // Place at configured hub spawn
                                        PositionData target;
                                        if (gFinal.spawnX != null && gFinal.spawnY != null && gFinal.spawnZ != null) {
                                            double tx = gFinal.spawnX;
                                            double tz = gFinal.spawnZ;
                                            if (Math.floor(tx) == tx) tx += 0.5; if (Math.floor(tz) == tz) tz += 0.5;
                                            target = new PositionData(tx, gFinal.spawnY, tz,
                                                    gFinal.spawnYaw != null ? gFinal.spawnYaw : player.getYaw(),
                                                    gFinal.spawnPitch != null ? gFinal.spawnPitch : player.getPitch());
                                        } else {
                                            net.minecraft.util.Identifier id = net.minecraft.util.Identifier.of(dimCandidate);
                                            net.minecraft.registry.RegistryKey<net.minecraft.world.World> key = net.minecraft.registry.RegistryKey.of(net.minecraft.registry.RegistryKeys.WORLD, id);
                                            net.minecraft.server.world.ServerWorld tw = player.getCommandSource().getServer().getWorld(key);
                                            if (tw != null) {
                                                net.minecraft.util.math.BlockPos sp = tw.getSpawnPoint().getPos();
                                                target = new PositionData(sp.getX() + 0.5, sp.getY(), sp.getZ() + 0.5, player.getYaw(), player.getPitch());
                                            } else {
                                                target = new PositionData(player.getX(), player.getY(), player.getZ(), player.getYaw(), player.getPitch());
                                            }
                                        }
                                        MultiWorldPositions.getPositionStorage().setPosition(player.getUuid(), dimCandidate, target);
                                        final PositionData hubTarget = target;
                                        final String hubDim = dimCandidate;
                                        player.getCommandSource().getServer().execute(() -> TeleportPlacement.placeExactlyOrNearby(player, hubDim, hubTarget));
                                    }
                                }
                                return ok ? 1 : 0;
                            })
            );
        }
    }
}
