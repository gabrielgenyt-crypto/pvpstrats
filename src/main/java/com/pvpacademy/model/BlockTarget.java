package com.pvpacademy.model;

import org.bukkit.block.BlockFace;

/**
 * Describes a block that was interacted with during a recording.
 *
 * <p>All coordinates are stored <em>relative to the recording's origin</em>
 * (the position of the admin who made the recording at tick 0).  This makes
 * recordings location-independent — they can be replayed anywhere.</p>
 *
 * <p>For {@link PlayerAction#RIGHT_CLICK_BLOCK}, {@link #material} holds the
 * Bukkit {@link org.bukkit.Material} name of the block that was placed (i.e.
 * the item in the admin's hand, converted to its block form).  During playback
 * the bot places this material at the computed world position.</p>
 *
 * <p>For {@link PlayerAction#LEFT_CLICK_BLOCK}, {@link #material} is the
 * material of the block that was struck (useful for context but not actively
 * used during playback).</p>
 *
 * @param relX      X offset from the recording origin
 * @param relY      Y offset from the recording origin
 * @param relZ      Z offset from the recording origin
 * @param face      which face of the block was targeted
 * @param material  Bukkit {@link org.bukkit.Material#name()} of the block
 */
public record BlockTarget(
    double relX,
    double relY,
    double relZ,
    String face,
    String material
) {
    /**
     * Convenience constructor that accepts a {@link BlockFace} enum value
     * directly and stores its name.
     */
    public BlockTarget(double relX, double relY, double relZ,
                       BlockFace face, String material) {
        this(relX, relY, relZ, face.name(), material);
    }
}
