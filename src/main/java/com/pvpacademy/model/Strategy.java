package com.pvpacademy.model;

import org.jetbrains.annotations.Nullable;

/**
 * A named PvP strategy that can optionally have a demo recording attached.
 *
 * <p>Strategies are persisted to {@code plugins/PvPAcademy/strategies.json}.
 * When a recording is linked via {@code /pvpa bot create <strategy> <recording>},
 * the {@link #recordingName} field is populated and training sessions against
 * this strategy will evaluate the student against that recording.</p>
 *
 * @param name          unique identifier used in commands (e.g. "dtap")
 * @param displayName   human-readable label shown in the UI
 * @param description   short explanation shown when the player starts training
 * @param recordingName name of the linked {@link Recording}, or null if not yet set
 * @param toleranceTicks allowed tick deviation for a PERFECT rating during evaluation
 * @param goodTolerance  allowed tick deviation for a GOOD rating during evaluation
 */
public record Strategy(
    String name,
    String displayName,
    String description,
    @Nullable String recordingName,
    int toleranceTicks,
    int goodTolerance
) {
    /** Default tolerance values used when creating a strategy without explicit config. */
    public static final int DEFAULT_PERFECT_TICKS = 2;
    public static final int DEFAULT_GOOD_TICKS    = 5;

    /**
     * Returns a copy of this strategy with the recording name updated.
     * Records are immutable, so linking a recording produces a new instance.
     */
    public Strategy withRecording(String newRecordingName) {
        return new Strategy(name, displayName, description,
                            newRecordingName, toleranceTicks, goodTolerance);
    }

    /** Returns {@code true} if a demo recording has been linked to this strategy. */
    public boolean hasRecording() {
        return recordingName != null && !recordingName.isBlank();
    }
}
