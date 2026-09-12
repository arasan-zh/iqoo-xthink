package `in`.arasan.xthink.guidance

import `in`.arasan.xthink.guidance.GuidanceConstants.ANALYSIS_INTERVAL_COOL_MS
import `in`.arasan.xthink.guidance.GuidanceConstants.ANALYSIS_INTERVAL_CRITICAL_MS
import `in`.arasan.xthink.guidance.GuidanceConstants.ANALYSIS_INTERVAL_HOT_MS
import `in`.arasan.xthink.guidance.GuidanceConstants.ANALYSIS_INTERVAL_WARM_MS
import `in`.arasan.xthink.guidance.GuidanceConstants.THERMAL_CRITICAL_HEADROOM
import `in`.arasan.xthink.guidance.GuidanceConstants.THERMAL_HOT_HEADROOM
import `in`.arasan.xthink.guidance.GuidanceConstants.THERMAL_HYSTERESIS
import `in`.arasan.xthink.guidance.GuidanceConstants.THERMAL_TIER_HOLD_MS
import `in`.arasan.xthink.guidance.GuidanceConstants.THERMAL_WARM_HEADROOM

/** How hot, in the terms the app acts on. */
enum class ThermalTier { COOL, WARM, HOT, CRITICAL }

/** What the app should do at a tier. */
data class ThermalPlan(
    val tier: ThermalTier,
    /** Minimum gap between analysed frames. The detector is the heat we control. */
    val analysisIntervalMs: Long,
    /** The lock game's motor. Off when warm enough that it is just more heat. */
    val hapticsOn: Boolean,
    /** Automatic shots. Each is a JPEG encode; off only at the top. */
    val autoCaptureOn: Boolean,
) {
    companion object {
        fun forTier(tier: ThermalTier): ThermalPlan = when (tier) {
            ThermalTier.COOL -> ThermalPlan(tier, ANALYSIS_INTERVAL_COOL_MS, hapticsOn = true, autoCaptureOn = true)
            ThermalTier.WARM -> ThermalPlan(tier, ANALYSIS_INTERVAL_WARM_MS, hapticsOn = true, autoCaptureOn = true)
            ThermalTier.HOT -> ThermalPlan(tier, ANALYSIS_INTERVAL_HOT_MS, hapticsOn = false, autoCaptureOn = true)
            ThermalTier.CRITICAL -> ThermalPlan(tier, ANALYSIS_INTERVAL_CRITICAL_MS, hapticsOn = false, autoCaptureOn = false)
        }
    }
}

/**
 * Turns thermal headroom into a plan, without flapping.
 *
 * The tier comes from [PowerManager.getThermalHeadroom] - 0 cold, 1.0 at the
 * throttling point - with the coarser thermal STATUS as an override, so a
 * phone that reports no headroom (NaN: unsupported, or polled too often)
 * still gets governed. Same discipline as every other decision here: a tier
 * change must persist [THERMAL_TIER_HOLD_MS], and stepping down needs
 * headroom [THERMAL_HYSTERESIS] below the threshold that stepped it up, so a
 * value hovering at 0.60 does not toggle the analysis rate every second.
 *
 * Pure. :app polls the PowerManager and applies the plan.
 */
class ThermalGovernor {

    var plan: ThermalPlan = ThermalPlan.forTier(ThermalTier.COOL)
        private set

    val tier: ThermalTier get() = plan.tier

    private var candidate: ThermalTier = ThermalTier.COOL
    private var candidateMs = 0L

    /**
     * @param headroom from getThermalHeadroom; NaN when unavailable.
     * @param statusLevel PowerManager.THERMAL_STATUS_*: 0 NONE, 1 LIGHT,
     *        2 MODERATE, 3 SEVERE, 4 CRITICAL, 5 EMERGENCY, 6 SHUTDOWN.
     */
    fun update(headroom: Float, statusLevel: Int, dtMs: Long): ThermalPlan {
        val dt = if (dtMs < 0L) 0L else dtMs
        val proposed = maxOf(tierFromHeadroom(headroom, tier), tierFromStatus(statusLevel))

        if (proposed == tier) {
            candidate = tier
            candidateMs = 0L
            return plan
        }
        if (proposed == candidate) {
            candidateMs += dt
            if (candidateMs >= THERMAL_TIER_HOLD_MS) {
                plan = ThermalPlan.forTier(proposed)
                candidateMs = 0L
            }
        } else {
            candidate = proposed
            candidateMs = dt
        }
        return plan
    }

    fun reset() {
        plan = ThermalPlan.forTier(ThermalTier.COOL)
        candidate = ThermalTier.COOL
        candidateMs = 0L
    }

    companion object {

        /**
         * Thresholds slide down by the hysteresis for any tier at or below
         * the current one, so leaving a tier is harder than entering it.
         */
        fun tierFromHeadroom(headroom: Float, current: ThermalTier): ThermalTier {
            if (headroom.isNaN()) return ThermalTier.COOL
            fun edge(base: Float, tier: ThermalTier) =
                if (current >= tier) base - THERMAL_HYSTERESIS else base
            return when {
                headroom >= edge(THERMAL_CRITICAL_HEADROOM, ThermalTier.CRITICAL) -> ThermalTier.CRITICAL
                headroom >= edge(THERMAL_HOT_HEADROOM, ThermalTier.HOT) -> ThermalTier.HOT
                headroom >= edge(THERMAL_WARM_HEADROOM, ThermalTier.WARM) -> ThermalTier.WARM
                else -> ThermalTier.COOL
            }
        }

        /** The status is coarse and lags; it is a floor, not the signal. */
        fun tierFromStatus(statusLevel: Int): ThermalTier = when {
            statusLevel >= 3 -> ThermalTier.CRITICAL   // SEVERE and above
            statusLevel == 2 -> ThermalTier.HOT        // MODERATE
            statusLevel == 1 -> ThermalTier.WARM       // LIGHT
            else -> ThermalTier.COOL
        }
    }
}
