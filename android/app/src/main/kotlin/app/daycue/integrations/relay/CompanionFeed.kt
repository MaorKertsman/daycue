package app.daycue.integrations.relay

import app.daycue.domain.signal.CompanionActivity
import app.daycue.domain.signal.CompanionGone
import app.daycue.domain.signal.CompanionState
import app.daycue.domain.signal.Signal
import java.time.Duration
import java.time.Instant

/**
 * Converts the relay's raw signed companion signals into domain signals (RELAY.md 4.5, COMPANION.md). The
 * phone verifies every signature itself with the companion key the relay lists; a signal that does not verify
 * is ignored. Semantics:
 *
 * - expiry = `observedAt + ttlSeconds`; a stale signal is not fed at all (the domain treats missing as Unknown);
 * - `ttlSeconds <= 30` is the companion's "paused / going away" marker (it sends 10 s). It is not a state
 *   report: it becomes [CompanionGone] at its `observedAt`, which makes the domain retract any earlier report
 *   at once (Activity = Unknown, an automatic session is suspended immediately, WRK-5), **even when the marker
 *   itself has already expired** (the phone may only see it minutes later). A marker older than
 *   [GONE_MAX_AGE] is ignored (it could only retract reports that are long stale anyway);
 * - several companions combine like the relay does: any fresh `active` wins, else `idle`, `locked`, `asleep`.
 *   A gone marker never outranks a real, fresh signal from another companion.
 */
object CompanionFeed {
    const val PAUSE_MARKER_MAX_TTL_S = 30
    val GONE_MAX_AGE: Duration = Duration.ofHours(1)

    enum class Reason { Ok, Stale, BadSignature, UnknownCompanion, UnknownState, PausedMarker }

    /** [activity] for a real fresh report; [gone] for a verified pause/gone marker; never both. */
    data class Checked(val signal: WireSignal, val reason: Reason, val activity: CompanionActivity?, val gone: CompanionGone? = null)

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
        if (sig.ttlSeconds <= PAUSE_MARKER_MAX_TTL_S) {
            if (observed.isBefore(now.minus(GONE_MAX_AGE)) || observed.isAfter(now.plusSeconds(120))) return Checked(sig, Reason.Stale, null)
            return Checked(sig, Reason.PausedMarker, null, CompanionGone(observed))
        }
        val expires = observed.plusSeconds(sig.ttlSeconds.toLong())
        if (!now.isBefore(expires)) return Checked(sig, Reason.Stale, null)
        return Checked(sig, Reason.Ok, CompanionActivity(state, observed, expires))
    }

    private fun rank(s: CompanionState) = when (s) { CompanionState.Active -> 0; CompanionState.Idle -> 1; CompanionState.Locked -> 2; CompanionState.Asleep -> 3 }

    /**
     * The one signal to feed the engine, or null = nothing to say (the engine lets its own track expire).
     * Real fresh reports win; only when there is none does a gone marker retract.
     */
    fun combine(checked: List<Checked>): Signal? {
        val real = checked.mapNotNull { it.activity }
        if (real.isNotEmpty()) {
            val best = real.minOf { rank(it.state) }
            return real.filter { rank(it.state) == best }.maxByOrNull { it.observedAt }
        }
        return checked.mapNotNull { it.gone }.maxByOrNull { it.observedAt }
    }
}
