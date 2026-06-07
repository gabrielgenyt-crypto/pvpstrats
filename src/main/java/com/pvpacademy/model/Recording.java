package com.pvpacademy.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A complete movement and action recording captured by an admin.
 *
 * <p>The recording stores the absolute world coordinates of the first frame
 * (the "origin") separately.  All {@link TickFrame} positions are relative
 * offsets from that origin so the recording can be replayed at any location.</p>
 *
 * <h3>Checkpoints</h3>
 * <p>Frames whose {@link TickFrame#action()} is not {@link PlayerAction#NONE}
 * are considered "checkpoints" — the moments the evaluation system scores
 * the student against.</p>
 */
public final class Recording {

    /** Unique name used as the file name and lookup key. */
    private String name;

    /**
     * Absolute world position of the admin at tick 0.
     * Used to translate relative frame positions to world coordinates during playback.
     */
    private double originX;
    private double originY;
    private double originZ;

    /** Name of the world this recording was made in. */
    private String worldName;

    /** All captured ticks, in order. */
    private final List<TickFrame> ticks = new ArrayList<>();

    // Gson needs a no-arg constructor
    public Recording() {}

    public Recording(String name, double originX, double originY, double originZ, String worldName) {
        this.name      = name;
        this.originX   = originX;
        this.originY   = originY;
        this.originZ   = originZ;
        this.worldName = worldName;
    }

    // ── Mutation ──────────────────────────────────────────────────────────────

    public void addFrame(TickFrame frame) {
        ticks.add(frame);
    }

    // ── Queries ───────────────────────────────────────────────────────────────

    public String getName()       { return name; }
    public double getOriginX()    { return originX; }
    public double getOriginY()    { return originY; }
    public double getOriginZ()    { return originZ; }
    public String getWorldName()  { return worldName; }

    public List<TickFrame> getTicks() {
        return Collections.unmodifiableList(ticks);
    }

    public int getTotalTicks() {
        return ticks.size();
    }

    /**
     * Returns all frames that carry a meaningful action (i.e. frames the
     * evaluation system treats as scoreable checkpoints).
     */
    public List<TickFrame> getCheckpoints() {
        return ticks.stream()
                    .filter(f -> f.action() != PlayerAction.NONE)
                    .toList();
    }
}
