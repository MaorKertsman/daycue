package app.daycue.integrations.relay

import app.daycue.domain.signal.CompanionActivity
import app.daycue.domain.signal.CompanionState
import java.time.Instant

/**
 * Converts the relay's raw signed companion signals into the domain's [CompanionActivity] (RELAY.md 4.5,
 * COMPANION.md). The phone verifies every signature itself with the companion key the relay lists; a signal
 * that does not verify is ignored. Semantics:
 *
 * - expiry = `observedAt + ttlSeconds`; a stale signal is not fed at all (the domain treats missing as Unknown);
 * - `ttlSeconds <= 30` is the companion's "paused / going away" marker (it sends 10 s): it is fed with its short
 *   expiry, so the state lapses to Unknown within seconds instead of 3 minutes. If the marker is already expired
 *   when we see it nothing is fed (the domain cannot retract an earlier fresh signal; see docs/setup/MCP.md);
 * - several companions combine like the relay does: any fresh `active` wins, else `idle`, `locked`, `asleep`.
 */
object CompanionFeed {
    const val PAUSE_MARKER_MAX_TTL_S = 30

    enum class Reason { Ok, Stale, BadSignature, UnknownCompanion, UnknownState, PausedMarker }
    data class Checked(val signal: WireSignal, val reason: Reason, val activity: CompanionActivity?)

    fun check(sig: WireSignal, companions: List<WireCompanion>, now: Instant): Checked {
        val co = companions.firstOrNull { it.id == sig.companionId } ?: return Checked(sig, Reason.UnknownCompanion, null)
        val state = when (sig.state) {
            "active" -> CompanionState.Active
            "idle" -> CompanionState.Idle
            "locked" -> CompanionState.Locked
            "asleep" -> CompanionState.Asleep
            else -> return Checked(sig, Reason.UnknownState, null)
        }
        val raw = sig.signatureOrSig ?: return Checked(sig, Reason.BadSignature, null)
        val ok = runCatching {
            EcdsaVerify.verify(Base64Url.decode(co.publicKey), SigningStrings.signal(sig.companionId, sig.state, sig.observedAt, sig.ttlSeconds), Base64Url.decode(raw))
        }.getOrDefault(false)
        if (!ok) return Checked(sig, Reason.BadSignature, null)
        val observed = Instant.ofEpochMilli(sig.observedAt)
        val expires = observed.plusSeconds(sig.ttlSeconds.toLong())
        if (!now.isBefore(expires)) return Checked(sig, Reason.Stale, null)
        val reason = if (sig.ttlSeconds <= PAUSE_MARKER_MAX_TTL_S) Reason.PausedMarker else Reason.Ok
        return Checked(sig, reason, CompanionActivity(state, observed, expires))
    }

    private fun rank(s: CompanionState) = when (s) { CompanionState.Active -> 0; CompanionState.Idle -> 1; CompanionState.Locked -> 2; CompanionState.Asleep -> 3 }

    /** The one signal to feed the engine (or null = nothing fresh, the engine keeps/lets expire its own track). */
    fun combine(checked: List<Checked>): CompanionActivity? {
        val live = checked.mapNotNull { it.activity }
        if (live.isEmpty()) return null
        // Paused markers never outrank real signals from other companions.
        val real = checked.filter { it.reason == Reason.Ok }.mapNotNull { it.activity }
        val pool = real.ifEmpty { live }
        val best = pool.minOf { rank(it.state) }
        val winners = pool.filter { rank(it.state) == best }
        return winners.maxByOrNull { it.observedAt }
    }
}
