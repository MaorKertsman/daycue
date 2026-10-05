package app.daycue.engine

import app.daycue.delivery.AlarmScreenLaunch
import app.daycue.delivery.NotificationDelivery
import app.daycue.delivery.NotificationSync
import app.daycue.delivery.Templates
import app.daycue.domain.config.ConfigCodec
import app.daycue.domain.config.Defaults
import app.daycue.domain.config.Language
import app.daycue.facade.ConfigTransfer
import app.daycue.facade.ImportParse
import app.daycue.integrations.relay.ActivityResponse
import app.daycue.integrations.relay.CompanionCode
import app.daycue.integrations.relay.CompanionDirectory
import app.daycue.integrations.relay.CompanionListResult
import app.daycue.integrations.relay.CompanionRevokeResult
import app.daycue.integrations.relay.GrantDecisionResponse
import app.daycue.integrations.relay.GrantsResponse
import app.daycue.integrations.relay.PullResponse
import app.daycue.integrations.relay.RelayApi
import app.daycue.integrations.relay.RelayException
import app.daycue.integrations.relay.SnapshotResponse
import app.daycue.integrations.relay.WireCompanion
import app.daycue.integrations.spotify.AlarmMusicState
import app.daycue.integrations.spotify.SpotifyAvailability
import app.daycue.integrations.spotify.SpotifyConnection
import app.daycue.integrations.spotify.SpotifyFailure
import app.daycue.integrations.spotify.RecoveryAction
import app.daycue.system.LanguagePolicy
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId

/** JVM tests for the 2026-10-05 platform fixes (VALIDATION D1, D4, D7, D8 and the UI facade gaps). */
class PlatformFixesTest {

    // ---- D1 -------------------------------------------------------------------------------------

    @Test
    fun `D1 shown keys - cue tags with the cue id, no group summaries, the playback FGS counts for its routine`() {
        val id = NotificationDelivery.NOTIFICATION_ID
        val shown = NotificationSync.shownKeys(
            listOf("habit:hydration" to id, "group:group-7" to id, null to 0xA1A, "med:a|2026-10-05|08:00" to id, "other" to 99),
            playbackKey = "routine:morning-routine",
        )
        assertEquals(setOf("habit:hydration", "med:a|2026-10-05|08:00", "routine:morning-routine"), shown)
    }

    // ---- D4 -------------------------------------------------------------------------------------

    private val export = ConfigTransfer.export(Defaults.config(), null, Instant.parse("2026-10-05T08:00:00Z"))

    @Test
    fun `D4 import rejects a newer formatVersion with a typed result and an unknown one as not a setup`() {
        assertTrue(ConfigTransfer.parse(export) is ImportParse.Ok)
        val newer = ConfigTransfer.parse(export.replace("\"formatVersion\": 1", "\"formatVersion\": 2"))
        assertTrue(newer.toString(), newer is ImportParse.UnsupportedSchema)
        assertEquals(2, (newer as ImportParse.UnsupportedSchema).formatVersion)
        for (bad in listOf("\"formatVersion\": 0", "\"formatVersion\": \"x\"", "\"formatVersionX\": 1")) {
            val r = ConfigTransfer.parse(export.replace("\"formatVersion\": 1", bad))
            assertEquals(bad, ImportParse.NotDayCue("unknown_format_version"), r)
        }
        // A bare config document (no wrapper) needs no formatVersion.
        assertTrue(ConfigTransfer.parse(ConfigCodec.encode(Defaults.config())) is ImportParse.Ok)
    }

    // ---- D7 -------------------------------------------------------------------------------------

    @Test
    fun `D7 the alarm screen is started directly only while the app is visible`() {
        assertTrue(AlarmScreenLaunch.startDirectly(appVisible = true, locked = false))
        assertFalse(AlarmScreenLaunch.startDirectly(appVisible = false, locked = false))
        assertFalse(AlarmScreenLaunch.startDirectly(appVisible = true, locked = true))
    }

    @Test
    fun `D12 alarm screen - the illustration flexes within bounds, is dropped when there is no room, no heads-up in the foreground`() {
        assertNull(app.daycue.ui.alarm.AlarmLayout.fieldHeightDp(40f)) // font 2.0 on a small screen: dropped, not a strip
        assertNull(app.daycue.ui.alarm.AlarmLayout.fieldHeightDp(119f))
        assertEquals(120f, app.daycue.ui.alarm.AlarmLayout.fieldHeightDp(120f))
        assertEquals(200f, app.daycue.ui.alarm.AlarmLayout.fieldHeightDp(200f))
        assertEquals(300f, app.daycue.ui.alarm.AlarmLayout.fieldHeightDp(900f)) // capped: no giant plane
        assertTrue(AlarmScreenLaunch.quietNotification(AlarmScreenLaunch.startDirectly(appVisible = true, locked = false)))
        assertFalse(AlarmScreenLaunch.quietNotification(AlarmScreenLaunch.startDirectly(appVisible = false, locked = false)))
    }

    /** VALIDATION D10(c) / DOMAIN.md: device region decides; the language only when the device has no region. */
    @Test
    fun `D10c work days from the device region, independent of the UI language`() {
        val sunThu = setOf(DayOfWeek.SUNDAY, DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY)
        val monFri = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
        // (per-app tag, device tag) -> work days
        val table = listOf(
            Triple("he", "he-IL", sunThu), Triple("en", "en-IL", sunThu), Triple("he", "en-IL", sunThu),
            Triple("he", "he-US", monFri), Triple("he", "en-US", monFri), Triple("en", "en-US", monFri),
            Triple("he", "he", sunThu), Triple(null, "he", sunThu), Triple(null, "en", monFri),
        )
        for ((perApp, device, want) in table) {
            val c = Defaults.config(LanguagePolicy.seed(perApp, device, true) { _, _ -> null })
            assertEquals("$perApp / $device", want, c.settings.workDays)
        }
    }

    // ---- D8 / first run -------------------------------------------------------------------------

    @Test
    fun `D8 the UI is the truth at start, a config change from elsewhere moves the UI`() {
        assertEquals(Language.he, LanguagePolicy.configFix(perAppTag = "he", deviceTag = "en-US", config = Language.en)) // set in system settings
        assertNull(LanguagePolicy.configFix("iw", "en-US", Language.he))
        assertEquals(Language.he, LanguagePolicy.configFix(null, "he-IL", Language.en)) // follows the phone
        assertEquals(Language.en, LanguagePolicy.configFix(null, "fr-FR", Language.he)) // unsupported phone language: English
        assertEquals("he", LanguagePolicy.uiFix(null, "en-US", Language.he)) // MCP / import set Hebrew
        assertNull(LanguagePolicy.uiFix("he", "en-US", Language.he))
    }

    @Test
    fun `first run seed - language from the app locale, work days from its region, device clock, localized names`() {
        val seed = LanguagePolicy.seed(perAppTag = null, deviceTag = "he-IL", use24Hour = true) { key, lang -> if (lang == Language.he && key == "template.place.home") "בית" else null }
        val c = Defaults.config(seed)
        assertEquals(Language.he, c.settings.language)
        assertEquals(setOf(DayOfWeek.SUNDAY, DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY), c.settings.workDays)
        assertEquals("בית", c.place(Defaults.HOME)!!.name)
        val us = Defaults.config(LanguagePolicy.seed("en-US", "en-US", false) { _, _ -> null })
        assertEquals(DayOfWeek.FRIDAY in us.settings.workDays, true)
        assertFalse(us.settings.use24Hour)
        assertEquals("IL", LanguagePolicy.region("he", "en-IL"))
    }

    @Test
    fun `use24Hour false formats times as h_mm AM_PM`() {
        val utc = ZoneId.of("UTC")
        assertEquals("20:05", Templates.formatArg("time", "20:05", utc, rtl = false))
        assertEquals("8:05 PM", Templates.formatArg("time", "20:05", utc, rtl = false, hour24 = false))
        assertEquals("8:05 AM", Templates.formatArg("at", "2026-10-05T08:05:00Z", utc, rtl = false, hour24 = false))
    }

    // ---- Spotify --------------------------------------------------------------------------------

    @Test
    fun `Spotify availability and connection state`() {
        assertEquals(SpotifyConnection.Unavailable, SpotifyAvailability.of(false, true, true, AlarmMusicState.Idle).connection)
        assertFalse(SpotifyAvailability.of(true, false, true, AlarmMusicState.Idle).available)
        val notInstalled = SpotifyAvailability.of(true, true, false, AlarmMusicState.Idle)
        assertTrue(notInstalled.available); assertFalse(notInstalled.enabled)
        assertEquals(SpotifyConnection.NotInstalled, notInstalled.connection)
        assertEquals(SpotifyConnection.Playing, SpotifyAvailability.of(true, true, true, AlarmMusicState.Playing).connection)
        val fell = SpotifyAvailability.of(true, true, true, AlarmMusicState.FellBack(SpotifyFailure.NoNetwork, RecoveryAction.CheckNetwork))
        assertEquals(SpotifyConnection.FellBack, fell.connection)
        assertEquals(SpotifyFailure.NoNetwork, fell.lastFailure)
    }

    // ---- Companions ----------------------------------------------------------------------------

    private class FakeApi(var revoke: () -> Unit = {}) : RelayApi {
        override suspend fun activity() = ActivityResponse(companions = listOf(WireCompanion("co_1", "pc", "AAAA"), WireCompanion("co_2", "laptop", "BBBB")))
        override suspend fun revokeCompanion(companionId: String) = revoke()
        override suspend fun pull(): PullResponse = error("unused")
        override suspend fun ack(commandId: String, body: JsonObject) = error("unused")
        override suspend fun putSnapshot(body: JsonElement): SnapshotResponse = error("unused")
        override suspend fun putPush(fcmToken: String?, wakeOnActivity: Boolean?) = error("unused")
        override suspend fun companionCode(): CompanionCode = error("unused")
        override suspend fun grants(): GrantsResponse = error("unused")
        override suspend fun decideGrant(grantId: String, body: JsonObject): GrantDecisionResponse = error("unused")
        override suspend fun unpairSelf() = error("unused")
    }

    @Test
    fun `companions are listed with a fingerprint and revoked, a relay without the endpoint is reported`() = runTest {
        val api = FakeApi()
        val dir = CompanionDirectory { api }
        assertEquals(CompanionListResult.Done, dir.refresh())
        assertEquals(listOf("pc", "laptop"), dir.companions.value.map { it.label })
        assertEquals(10, dir.companions.value.first().fingerprint.length)
        assertEquals(CompanionRevokeResult.Done, dir.revoke("co_1"))
        assertEquals(listOf("co_2"), dir.companions.value.map { it.id })
        api.revoke = { throw RelayException.Http(404, "not_found", "no route") }
        assertEquals(CompanionRevokeResult.NotSupportedByRelay, dir.revoke("co_2"))
        api.revoke = { throw RelayException.Http(404, "unknown_companion", "") }
        assertEquals(CompanionRevokeResult.NotFound, dir.revoke("co_2"))
        assertEquals(CompanionRevokeResult.NotPaired, CompanionDirectory { null }.revoke("x"))
    }
}
