package com.pvpacademy.model;

/**
 * Discrete player actions captured during a recording session.
 *
 * <p>Each server tick carries at most one action.  The tick-recorder listens
 * for events between ticks and stores the most-significant one that occurred
 * (priority: block interactions > air interactions > NONE).</p>
 */
public enum PlayerAction {

    /** No notable action this tick. */
    NONE,

    /** Left-click with nothing in range (air). Typically a melee swing. */
    LEFT_CLICK_AIR,

    /**
     * Left-click on a block (e.g. breaking or striking a block).
     * Paired with a {@link BlockTarget} recording the targeted block.
     */
    LEFT_CLICK_BLOCK,

    /** Right-click with nothing in range (air). */
    RIGHT_CLICK_AIR,

    /**
     * Right-click on a block face (e.g. placing a block, activating a button).
     * Paired with a {@link BlockTarget} recording what was placed and where.
     */
    RIGHT_CLICK_BLOCK,

    /**
     * The player changed their selected hotbar slot this tick.
     * The new slot index is stored in {@link TickFrame#slot()}.
     */
    SLOT_CHANGE
}
