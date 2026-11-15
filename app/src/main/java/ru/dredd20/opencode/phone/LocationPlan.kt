package ru.dredd20.opencode.phone

/** Pure part of termux-location: which providers to ask and whether to ask for a fresh fix. */
data class LocationPlan(val fresh: Boolean, val provider: String?, val timeoutSec: Int) {
    companion object {
        const val DEFAULT_TIMEOUT_S = 15
        private val PREFERRED = listOf("fused", "gps", "network")

        /** termux-location -r once|last|updates (updates is treated as once), -p gps|network|fused|passive. */
        fun of(request: String?, provider: String?, timeoutSec: Int): LocationPlan = LocationPlan(
            fresh = request?.trim()?.lowercase() != "last",
            provider = provider?.trim()?.lowercase()?.takeIf { it.isNotEmpty() },
            timeoutSec = timeoutSec.coerceIn(1, 60),
        )

        /** Enabled providers in preferred order; "passive" only if asked for explicitly. */
        fun order(enabled: List<String>, wanted: String?): List<String> {
            if (wanted != null) return enabled.filter { it == wanted }
            val known = PREFERRED.filter { it in enabled }
            val rest = enabled.filter { it !in PREFERRED && it != "passive" }
            return known + rest
        }
    }
}
