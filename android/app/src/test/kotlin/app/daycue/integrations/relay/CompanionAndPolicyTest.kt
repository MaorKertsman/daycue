package app.daycue.integrations.relay

import app.daycue.domain.config.Defaults
import app.daycue.domain.config.Place
import app.daycue.domain.config.SessionStart
import app.daycue.domain.config.SessionKind
import app.daycue.domain.context.ContextState
import app.daycue.domain.context.PlaceTrack
import app.daycue.domain.context.PlaceValue
import app.daycue.domain.engine.EngineState
import app.daycue.domain.engine.Event
import app.daycue.domain.signal.CompanionState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

class CompanionAndPolicyTest {
    private val now = Instant.parse("2026-10-05T07:00:00Z")
    private val nowMs = now.toEpochMilli()

    private fun derToRaw(der: ByteArray): ByteArray {
        var i = 3; val rl = der[i++].toInt(); val r = der.copyOfRange(i, i + rl); i += rl + 1
        val sl = der[i++].toInt(); val s = der.copyOfRange(i, i + sl)
        fun fix(b: ByteArray): ByteArray { val t = b.dropWhile { it == 0.toByte() }.toByteArray(); return ByteArray(32 - t.size) + t }
        return fix(r) + fix(s)
    }

    private class Co(val id: String, val signer: SoftwareSigner = SoftwareSigner()) {
        val wire get() = WireCompanion(id, "pc", Base64Url.encode(signer.publicKeySpki()))
        fun signal(state: String, observedAt: Long, ttl: Int, raw: Boolean = false, derToRaw: (ByteArray) -> ByteArray = { it }): WireSignal {
            val sig = signer.sign(SigningStrings.signal(id, state, observedAt, ttl).toByteArray())
            return WireSignal(id, state, observedAt, ttl, Base64Url.encode(if (raw) derToRaw(sig) else sig))
        }
    }

    @Test fun validSignalBecomesCompanionActivityWithTtlExpiry() {
        val co = Co("co_1")
        val c = CompanionFeed.check(co.signal("active", nowMs - 10_000, 180), listOf(co.wire), now)
        assertEquals(CompanionFeed.Reason.Ok, c.reason)
        assertEquals(CompanionState.Active, c.activity!!.state)
        assertEquals(Instant.ofEpochMilli(nowMs - 10_000 + 180_000), c.activity!!.expiresAt)
        // asleep carries a 600 s ttl
        assertEquals(Instant.ofEpochMilli(nowMs - 1_000 + 600_000), CompanionFeed.check(co.signal("asleep", nowMs - 1_000, 600), listOf(co.wire), now).activity!!.expiresAt)
    }

    @Test fun dotNetRawSignatureFormatIsAccepted() {
        val co = Co("co_1")
        assertEquals(CompanionFeed.Reason.Ok, CompanionFeed.check(co.signal("idle", nowMs - 1000, 180, raw = true, derToRaw = ::derToRaw), listOf(co.wire), now).reason)
    }

    @Test fun staleSignalIsUnknownAndNeverFed() {
        val co = Co("co_1")
        val c = CompanionFeed.check(co.signal("active", nowMs - 200_000, 180), listOf(co.wire), now)
        assertEquals(CompanionFeed.Reason.Stale, c.reason)
        assertNull(c.activity)
        assertNull(CompanionFeed.combine(listOf(c)))
    }

    @Test fun forgedOrTamperedOrUnknownCompanionIsIgnored() {
        val co = Co("co_1"); val other = Co("co_2")
        assertEquals(CompanionFeed.Reason.BadSignature, CompanionFeed.check(other.signal("active", nowMs, 180).copy(companionId = "co_1"), listOf(co.wire), now).reason)
        val good = co.signal("idle", nowMs, 180)
        assertEquals(CompanionFeed.Reason.BadSignature, CompanionFeed.check(good.copy(state = "active"), listOf(co.wire), now).reason)
        assertEquals(CompanionFeed.Reason.BadSignature, CompanionFeed.check(good.copy(ttlSeconds = 600), listOf(co.wire), now).reason)
        assertEquals(CompanionFeed.Reason.UnknownCompanion, CompanionFeed.check(good, emptyList(), now).reason)
        assertEquals(CompanionFeed.Reason.UnknownState, CompanionFeed.check(good.copy(state = "dancing"), listOf(co.wire), now).reason)
    }

    @Test fun shortTtlIsThePausedMarkerAndNeverOutranksRealSignals() {
        val a = Co("co_1"); val b = Co("co_2")
        val paused = CompanionFeed.check(a.signal("active", nowMs - 1000, 10), listOf(a.wire, b.wire), now)
        assertEquals(CompanionFeed.Reason.PausedMarker, paused.reason)
        assertEquals(Instant.ofEpochMilli(nowMs + 9000), paused.activity!!.expiresAt)
        val idle = CompanionFeed.check(b.signal("idle", nowMs - 2000, 180), listOf(a.wire, b.wire), now)
        assertEquals(CompanionState.Idle, CompanionFeed.combine(listOf(paused, idle))!!.state)
        // an expired paused marker feeds nothing (the domain cannot retract)
        assertNull(CompanionFeed.check(a.signal("active", nowMs - 20_000, 10), listOf(a.wire), now).activity)
    }

    @Test fun severalCompanionsCombineLikeTheRelay() {
        val a = Co("co_1"); val b = Co("co_2"); val cs = listOf(a.wire, b.wire)
        fun comb(sa: String, sb: String) = CompanionFeed.combine(listOf(
            CompanionFeed.check(a.signal(sa, nowMs - 5000, 180), cs, now), CompanionFeed.check(b.signal(sb, nowMs - 1000, 180), cs, now)))!!.state
        assertEquals(CompanionState.Active, comb("active", "locked"))
        assertEquals(CompanionState.Idle, comb("asleep", "idle"))
        assertEquals(CompanionState.Locked, comb("locked", "asleep"))
    }

    // ---- integration with the engine (acceptance scenarios 8 and 9 at the relay level) -------------------

    @Test fun sustainedActiveSignalStartsAnAutoSessionAndStaleOneSuspendsIt() = runBlocking {
        val h = Harness(settings = RelaySettings(useCompanionActivity = true))
        val co = Co("co_1")
        // Saved place with auto-start; we are "at" it via geofence.
        val cfg0 = h.host.ensureLoaded().config
        h.host.applyOps(listOf(app.daycue.domain.edit.ConfigOp.UpsertPlace(Place("office", "Office", center = app.daycue.domain.config.GeoPoint(1.0, 1.0),
            allowedActivities = setOf(SessionKind.Working), sessionStart = SessionStart.AutoStart))), cfg0.version, "ui")
        h.host.dispatch(Event.SignalObserved(app.daycue.domain.signal.GeofenceSnapshot(setOf("office"), h.clock.now().minusSeconds(3600))))
        h.clock.advance(Duration.ofMinutes(1))
        // companion reports active for 6 minutes (sustained >= 5 min), signals keep fresh
        var t = h.clock.now()
        repeat(7) {
            h.relay.activity = ActivityResponse(signals = listOf(co.signal("active", h.clock.now().toEpochMilli(), 180)), companions = listOf(co.wire))
            assertEquals(SyncStatus.Ok, h.client.sync("hb$it").status)
            h.clock.advance(Duration.ofMinutes(1))
        }
        h.host.dispatch(Event.Tick)
        val s = h.host.ensureLoaded().state.context
        assertNotNull("auto session started from companion signals", s.session)
        assertTrue(h.client.status.value.companion!!.contains("active"))
        // the companion goes silent: signal turns stale -> nothing fed, status says unknown
        h.clock.advance(Duration.ofMinutes(4))
        h.client.sync("stale")
        assertTrue(h.client.status.value.companion!!.contains("stale"))
    }

    @Test fun activityIsNotFetchedWhenNotOptedIn() = runBlocking {
        val h = Harness()
        val co = Co("co_1")
        h.relay.activity = ActivityResponse(signals = listOf(co.signal("active", nowMsOf(h), 180)), companions = listOf(co.wire))
        h.client.sync("t")
        assertNull(h.client.status.value.companion)
    }

    private fun nowMsOf(h: Harness) = h.clock.now().toEpochMilli()

    // ---- frequent check policy ------------------------------------------------------------------------

    @Test fun frequentCheckOnlyAtEnabledPlaceInsidePermittedHours() {
        val cfg = Defaults.config(app.daycue.domain.config.Language.en).let {
            it.copy(places = listOf(Place("office", "Office", center = app.daycue.domain.config.GeoPoint(1.0, 1.0), sessionStart = SessionStart.Suggest, allowedActivities = setOf(SessionKind.Working)),
                Place("cafe", "Cafe", sessionStart = SessionStart.Off)))
        }
        fun st(p: PlaceValue) = EngineState(context = ContextState(place = PlaceTrack(p)))
        val on = RelaySettings(frequentCheck = true, useCompanionActivity = true)
        val zone = ZoneId.of("Asia/Jerusalem")
        val monday10 = Instant.parse("2026-10-05T07:00:00Z")   // 10:00 local, Monday
        val monday21 = Instant.parse("2026-10-05T18:00:00Z")   // 21:00 local
        val saturday10 = Instant.parse("2026-10-10T07:00:00Z")
        assertTrue(FrequentCheckPolicy.shouldPoll(cfg, st(PlaceValue.saved("office")), on, monday10, zone))
        assertFalse("outside permitted hours", FrequentCheckPolicy.shouldPoll(cfg, st(PlaceValue.saved("office")), on, monday21, zone))
        assertFalse("outside permitted days", FrequentCheckPolicy.shouldPoll(cfg, st(PlaceValue.saved("office")), on, saturday10, zone))
        assertFalse("not a work place", FrequentCheckPolicy.shouldPoll(cfg, st(PlaceValue.saved("cafe")), on, monday10, zone))
        assertFalse("elsewhere", FrequentCheckPolicy.shouldPoll(cfg, st(PlaceValue.ELSEWHERE), on, monday10, zone))
        assertFalse("opt-in off", FrequentCheckPolicy.shouldPoll(cfg, st(PlaceValue.saved("office")), on.copy(frequentCheck = false), monday10, zone))
        assertFalse("kill switch", FrequentCheckPolicy.shouldPoll(cfg, st(PlaceValue.saved("office")), on.copy(enabled = false), monday10, zone))
        assertFalse("needs companion use", FrequentCheckPolicy.shouldPoll(cfg, st(PlaceValue.saved("office")), on.copy(useCompanionActivity = false), monday10, zone))
    }

    // ---- pairing text -----------------------------------------------------------------------------------

    @Test fun pairingLinkParsingAndUrlPolicy() {
        assertEquals("ABCDE-FGHJK", PairingLink.parse("ABCDE-FGHJK").code)
        val p = PairingLink.parse("daycue://pair?relay=https%3A%2F%2Frelay.example.com&code=ABCDE-FGHJK")
        assertEquals("https://relay.example.com", p.relayUrl); assertEquals("ABCDE-FGHJK", p.code)
        assertNull(PairingLink.validate("https://relay.example.com", false))
        assertEquals(PairResult.CleartextNotAllowed, PairingLink.validate("http://10.0.2.2:8787", false))
        assertNull(PairingLink.validate("http://10.0.2.2:8787", true))
        assertEquals(PairResult.InvalidUrl, PairingLink.validate("ftp://x", true))
    }
}
