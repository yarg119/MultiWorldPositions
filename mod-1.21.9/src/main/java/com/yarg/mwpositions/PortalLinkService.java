package com.yarg.mwpositions;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 1.21.9 overlay: Minimal stub to provide portal transfer suppression without custom portal logic.
 */
public class PortalLinkService {
    private static final java.util.Set<java.util.UUID> suppressNextRestore = java.util.Collections.newSetFromMap(new ConcurrentHashMap<>());
    public static void markPortalTransfer(java.util.UUID id) { suppressNextRestore.add(id); }
    public static boolean consumePortalTransfer(java.util.UUID id) { return suppressNextRestore.remove(id); }

    public void tick(net.minecraft.server.MinecraftServer server) {
        // no-op: custom portal logic disabled in this overlay
    }
}
