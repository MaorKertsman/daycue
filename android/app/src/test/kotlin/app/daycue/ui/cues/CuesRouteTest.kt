package app.daycue.ui.cues

import app.daycue.domain.config.Defaults
import app.daycue.domain.config.Medication
import java.time.LocalTime
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull

class CuesRouteTest {
    private val cfg = Defaults.config().let { c ->
        c.copy(medications = listOf(Medication(id = "med-1", label = "Example vitamin", times = listOf(LocalTime.of(8, 0)))))
    }

    @Test fun habitKeyOpensEditor() = assertEquals(listOf("habit/sunscreen"), resolveStartItem("habit:sunscreen", cfg))

    @Test fun doseKeyOpensMedicationList() = assertEquals(listOf("meds"), resolveStartItem("med:med-1|2026-10-05|08:00", cfg))

    @Test fun mergedMedicationCueOpensList() = assertEquals(listOf("meds"), resolveStartItem("med:merged", cfg))

    @Test fun routineKeyOpensPlayback() = assertEquals(listOf("routines", "routine/morning-routine/play"), resolveStartItem("routine:morning-routine", cfg))

    @Test fun routinePromptOpensPlayback() = assertEquals(listOf("routines", "routine/morning-routine/play"), resolveStartItem("routine-prompt:morning-routine", cfg))

    @Test fun alarmKeyOpensEditor() = assertEquals(listOf("alarms", "alarm/morning-alarm"), resolveStartItem("alarm:morning-alarm", cfg))

    @Test fun postureOpensLiveControl() = assertEquals(listOf("posture", "posture/live"), resolveStartItem("posture", cfg))

    @Test fun bareIdsAreResolvedAgainstTheConfig() {
        assertEquals(listOf("habit/hydration"), resolveStartItem("hydration", cfg))
        assertEquals(listOf("meds", "med/med-1"), resolveStartItem("med-1", cfg))
        assertEquals(listOf("routines", "routine/morning-routine"), resolveStartItem("morning-routine", cfg))
        assertEquals(listOf("alarms", "alarm/morning-alarm"), resolveStartItem("morning-alarm", cfg))
    }

    @Test fun unknownOrEmptyKeysDoNothing() {
        assertNull(resolveStartItem("", cfg))
        assertNull(resolveStartItem("habit:nope", cfg))
        assertNull(resolveStartItem("something-else", cfg))
    }

    @Test fun literalRouteStackIsAccepted() = assertEquals(listOf("meds", "med/history"), resolveStartItem("route:meds,med/history", cfg))

    /** Regression for B1: routes with a missing id segment must resolve to a screen, never throw (parts[1]). */
    @Test fun routesWithoutIdSegmentFallBackSafely() {
        for (r in listOf("habit", "habit/", "med", "routine", "alarm", "routine//play", "posture/", "")) {
            assertNull(r, routeArg(r))
        }
        assertEquals("x", routeArg("habit/x"))
    }
}
