package com.pvpacademy.model;

import org.jetbrains.annotations.Nullable;

/**
 * A snapshot of the admin's state at a single server tick during a recording.
 *
 * <h3>Position encoding</h3>
 * <p>{@code relX}, {@code relY}, {@code relZ} are stored as offsets from the
 * recording origin (the admin's position at tick 0).  {@code yaw} and
 * {@code pitch} are absolute camera angles (degrees).</p>
 *
 * <h3>Action encoding</h3>
 * <p>At most one {@link PlayerAction} per tick is recorded (highest-priority
 * action wins).  If an action involves a block, {@link #blockTarget()} is
 * non-null and contains the block's relative position and material.</p>
 *
 * @param tick        sequential tick index starting at 0
 * @param relX        X offset from the recording origin
 * @param relY        Y offset from the recording origin
 * @param relZ        Z offset from the recording origin
 * @param yaw         camera yaw in degrees
 * @param pitch       camera pitch in degrees
 * @param action      what the admin did this tick
 * @param slot        hotbar slot (0–8) held at the END of this tick
 * @param blockTarget block details when action is a block-click; otherwise null
 */
public record TickFrame(
    int tick,
    double relX,
    double relY,
    double relZ,
    float yaw,
    float pitch,
    PlayerAction action,
    int slot,
    @Nullable BlockTarget blockTarget
) {}
