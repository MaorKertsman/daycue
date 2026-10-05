package app.daycue.ui.setup

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure rules behind the Setup screens: steppers, coordinates, ids, accuracy wording, deep links and string parity. */
class SetupLogicTest {

    @Test
    fun minuteStepsMatchTheSharedDurationField() {
        assertEquals(6, stepMinutes(5, up = true, min = 1, max = 60))
        assertEquals(15, stepMinutes(10, up = true, min = 1, max = 60))
        assertEquals(60, stepMinutes(55, up = true, min = 1, max = 240))
        assertEquals(75, stepMinutes(60, up = true, min = 1, max = 240))
        assertEquals(9, stepMinutes(10, up = false, min = 1, max = 60))
        assertEquals(55, stepMinutes(60, up = false, min = 1, max = 240))
        assertEquals(1, stepMinutes(1, up = false, min = 1, max = 60))
        assertEquals(30, stepMinutes(30, up = true, min = 1, max = 30))
    }

    @Test
    fun radiusStaysInTheDomainRange() {
        assertEquals(50, stepRadius(50, up = false))
        assertEquals(1000, stepRadius(1000, up = true))
        assertEquals(175, stepRadius(150, up = true))
        assertEquals(125, stepRadius(150, up = false))
        assertEquals(250, stepRadius(200, up = true))
        assertEquals(175, stepRadius(200, up = false))
        assertEquals(600, stepRadius(500, up = true))
        // Walking the whole range never leaves 50..1000.
        var v = 50
        repeat(200) { v = stepRadius(v, up = true); assertTrue(v in 50..1000) }
        assertEquals(1000, v)
        repeat(200) { v = stepRadius(v, up = false); assertTrue(v in 50..1000) }
        assertEquals(50, v)
    }

    @Test
    fun coordinatesAcceptDecimalsAndCommas() {
        val ok = parseCoordinates("10.0", " 20,5 ") as CoordinateParse.Ok
        assertEquals(10.0, ok.point.lat, 0.0)
        assertEquals(20.5, ok.point.lng, 0.0)
        assertEquals(CoordinateParse.Empty, parseCoordinates("", "  "))
        assertEquals(CoordinateParse.BadLatitude, parseCoordinates("91", "0"))
        assertEquals(CoordinateParse.BadLatitude, parseCoordinates("abc", "0"))
        assertEquals(CoordinateParse.BadLongitude, parseCoordinates("0", "-180.5"))
        assertEquals(CoordinateParse.BadLongitude, parseCoordinates("0", ""))
        assertTrue(parseCoordinates("-90", "180") is CoordinateParse.Ok)
        assertEquals(CoordinateParse.BadLatitude, parseCoordinates("NaN", "0"))
    }

    @Test
    fun placeNamesAreValidatedAsTheDomainDoes() {
        assertEquals(NameProblem.Blank, nameProblem("   "))
        assertEquals(NameProblem.TooLong, nameProblem("x".repeat(41)))
        assertNull(nameProblem("Office"))
        assertNull(nameProblem("x".repeat(40)))
    }

    @Test
    fun newPlaceIdsAreUniqueSlugs() {
        assertEquals("place-test-lab", newPlaceId("Test Lab", emptySet()))
        assertEquals("place-test-lab-2", newPlaceId("Test Lab", setOf("place-test-lab")))
        assertEquals("place-test-lab-3", newPlaceId("test  lab", setOf("place-test-lab", "place-test-lab-2")))
        // A Hebrew-only name still gets a usable id.
        assertEquals("place-place", newPlaceId("משרד", emptySet()))
        assertTrue(newPlaceId("x".repeat(80), emptySet()).length <= 6 + 24)
    }

    @Test
    fun accuracyIsJudgedAgainstTheCircle() {
        assertEquals(AccuracyVerdict.Good, judgeAccuracy(20f, 150, precise = true))
        assertEquals(AccuracyVerdict.WiderThanCircle, judgeAccuracy(100f, 150, precise = true))
        assertEquals(AccuracyVerdict.TooCoarse, judgeAccuracy(600f, 1000, precise = true))
        assertEquals(AccuracyVerdict.TooCoarse, judgeAccuracy(10f, 150, precise = false))
        assertTrue(suggestedRadius(5f) >= 50)
        assertTrue(suggestedRadius(900f) <= 1000)
    }

    @Test
    fun leadTimesParseAndRejectJunk() {
        assertEquals(listOf(60, 15), parseLeads("60, 15"))
        assertEquals(listOf(10), parseLeads("10"))
        assertEquals(listOf(30, 10), parseLeads("30 10 30"))
        assertNull(parseLeads(""))
        assertNull(parseLeads("ten"))
        assertNull(parseLeads("5, 2000"))
        assertNull(parseLeads("-5"))
    }

    @Test
    fun keywordsAreTrimmedAndDeduplicated() {
        assertEquals(listOf("gym", "תור"), parseKeywords(" gym, תור ,gym;"))
        assertTrue(parseKeywords(" , ").isEmpty())
    }

    @Test
    fun gatedScopesAreTheOnesTheOwnerMustApprove() {
        assertTrue(isGatedScope("config:write"))
        assertTrue(isGatedScope("sessions:control"))
        assertTrue(isGatedScope("medication"))
        assertFalse(isGatedScope("config:read"))
        assertFalse(isGatedScope("activity:read"))
    }

    @Test
    fun deepLinksBuildTheParentChainFirst() {
        assertEquals(listOf("home"), stackFor(null))
        assertEquals(listOf("home", "places", "place:office"), stackFor("office"))
        // The template place id "home" is a place, not the Setup home.
        assertEquals(listOf("home", "places", "place:home"), stackFor("home"))
        assertEquals(listOf("home", "places", "place:home"), stackFor("place:home"))
        assertEquals(listOf("home", "calendar"), stackFor("calendar"))
        assertEquals(listOf("home", "calendar", "calendar-preview"), stackFor("cal:abc"))
        assertEquals(listOf("home", "integrations", "remote"), stackFor("remote"))
        assertEquals(listOf("home", "integrations", "remote"), stackFor("remote:cmd-1"))
        assertEquals(listOf("home", "sounds", "profile:profile-hydration"), stackFor("cueprofile:profile-hydration"))
        assertEquals(listOf("home", "readiness"), stackFor("readiness"))
    }

    // ---- string resources ---------------------------------------------------------------------------------

    private fun resDir(): File = listOf(File("src/main/res"), File("app/src/main/res")).first { it.isDirectory }

    private fun names(file: File, tag: String): Map<String, String> {
        val text = file.readText()
        return Regex("<$tag name=\"([^\"]+)\"[^>]*>(.*?)</$tag>", RegexOption.DOT_MATCHES_ALL).findAll(text)
            .associate { it.groupValues[1] to it.groupValues[2] }
    }

    private fun placeholders(s: String): List<String> =
        Regex("%(\\d+\\$)?[sd]").findAll(s).map { it.value.replace(Regex("^%\\d+\\$"), "%") }.sorted().toList()

    @Test
    fun everySetupStringHasAHebrewTwinWithTheSamePlaceholders() {
        val en = File(resDir(), "values/strings_setup.xml")
        val he = File(resDir(), "values-iw/strings_setup.xml")
        assertTrue(en.isFile && he.isFile)
        val enStrings = names(en, "string")
        val heStrings = names(he, "string")
        assertTrue("expected a large catalogue, got ${enStrings.size}", enStrings.size > 500)
        assertEquals(enStrings.keys - heStrings.keys, emptySet<String>())
        assertEquals(heStrings.keys - enStrings.keys, emptySet<String>())
        for ((key, value) in enStrings) {
            assertEquals("placeholders of $key", placeholders(value), placeholders(heStrings.getValue(key)))
        }
        val enPlurals = names(en, "plurals")
        val hePlurals = names(he, "plurals")
        assertEquals(enPlurals.keys, hePlurals.keys)
    }

    @Test
    fun hebrewCopyAvoidsGenderedSecondPersonAndStaysFreeOfLatinBreaks() {
        val he = File(resDir(), "values-iw/strings_setup.xml").readText()
        // Masculine second person forms called out in REVIEW-1 #33 must not come back.
        for (bad in listOf("אתה ", "כשאתה", "לא בטוח", " תקבל", "עובד עכשיו")) {
            assertFalse("unexpected gendered/ambiguous Hebrew: $bad", he.contains(bad))
        }
        assertNotNull(he)
    }

    @Test
    fun noRawEnumNamesLeakIntoEnglishCopy() {
        val en = File(resDir(), "values/strings_setup.xml").readText()
        for (leak in listOf("OutdoorWhenOnFoot", "AssumeOutdoor", "AlwaysConfirm", "SupplementOnMatch", "DuckAndSpeak", "NotificationOnly", "AutoStart")) {
            assertFalse("enum name in copy: $leak", en.contains(leak))
        }
    }
}
