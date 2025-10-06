package com.yarg.mwpositions;

public final class WorldGroup {
    public String id;
    public String overworld; // required
    public String nether;    // optional
    public String end;       // optional
    public LinkPortals linkPortals = new LinkPortals();
    public boolean inventoryProfile = false;

    // Legacy portal fields (ignored when enablePortals=false)
    public double netherScaleOverworldToNether = 0.125; // x/8, z/8
    public double netherScaleNetherToOverworld = 8.0;   // x*8, z*8
    public int portalSearchRadius = 64;
    public boolean createPortalIfMissing = false;

    // Optional hard-coded spawn for the group's primary world (usually overworld)
    public Double spawnX; // nullable
    public Double spawnY;
    public Double spawnZ;
    public Float spawnYaw;   // optional
    public Float spawnPitch; // optional
}
