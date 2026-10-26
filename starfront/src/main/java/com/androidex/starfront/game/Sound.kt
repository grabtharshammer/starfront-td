package com.androidex.starfront.game

/** Sound effects the game can ask for. [volume] is the base loudness before positioning. */
enum class Sfx(val volume: Float) {
    PULSE(0.3f),
    PLASMA(0.55f),
    IMPACT(0.4f),
    CRYO(0.35f),
    TESLA(0.5f),
    RAIL(0.7f),
    EXPLODE(0.45f),
    EXPLODE_BIG(1f),
    BARRIER(0.45f),
    CORE_HIT(0.9f),
    BUILD(0.6f),
    UPGRADE(0.6f),
    EFFICIENCY(0.75f),
    SELL(0.55f),
    DENIED(0.5f),
    WAVE_START(0.7f),
    AIR_RAID(0.75f),
    WAVE_CLEAR(0.6f),
    SECTOR_OPEN(0.8f),
    GAME_OVER(0.9f),
}

/**
 * A sound the game wants played at world position ([x], [y]) in cells. Non-positional sounds
 * (UI feedback, wave announcements) have NaN coordinates and always play at full volume.
 */
class SoundEvent(val sfx: Sfx, val x: Float = Float.NaN, val y: Float = Float.NaN) {
    val positional get() = !x.isNaN()
}
