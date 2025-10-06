package com.yarg.mwpositions;

public class PositionData {
    public final double x, y, z;
    public final float yaw, pitch;
    public final long timestamp;

    public PositionData(double x, double y, double z, float yaw, float pitch) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.pitch = pitch;
        this.timestamp = System.currentTimeMillis();
    }

    @Override
    public String toString() {
        return String.format("(%.2f, %.2f, %.2f) [yaw=%.1f, pitch=%.1f]",
                x, y, z, yaw, pitch);
    }
}