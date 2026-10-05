package app.daycue.ui.app

import org.junit.Assert.assertEquals
import org.junit.Test

/** Deep link routing for daycue://open/<target>?item=... (APP_API section 4). */
class AppRouteTest {
    @Test
    fun cueTargetsGoToCuesWithTheItemKey() {
        assertEquals(AppRoute.Cues("routine:morning-routine"), AppRoute.from("routine", "routine:morning-routine"))
        assertEquals(AppRoute.Cues("med:merged"), AppRoute.from("medication", "med:merged"))
        assertEquals(AppRoute.Cues("med:a|2026-10-05|08:00"), AppRoute.from("dose", "med:a|2026-10-05|08:00"))
        assertEquals(AppRoute.Cues("alarm:morning-alarm"), AppRoute.from("alarm", "alarm:morning-alarm"))
        assertEquals(AppRoute.Cues("posture"), AppRoute.from("posture", "posture"))
        assertEquals(AppRoute.Cues(null), AppRoute.from("medication", null))
        assertEquals(AppRoute.Cues(null), AppRoute.from("alarm", " "))
    }

    @Test
    fun setupTargetsGoToSetup() {
        assertEquals(AppRoute.Setup("cal:abc"), AppRoute.from("calendar", "cal:abc"))
        assertEquals(AppRoute.Setup(null), AppRoute.from("places", null))
        assertEquals(AppRoute.Setup(null), AppRoute.from("remote", null))
    }

    @Test
    fun itemOpensDetailAndContextOpensTheSheetOnToday() {
        assertEquals(AppRoute.ItemDetail("habit:sunscreen"), AppRoute.from("item", "habit:sunscreen"))
        assertEquals(AppRoute.Today, AppRoute.from("item", null))
        assertEquals(AppRoute.ContextSheet, AppRoute.from("context", "session:home"))
        assertEquals(AppRoute.Readiness, AppRoute.from("readiness", null))
    }

    @Test
    fun unknownOrMissingTargetsFallBackToToday() {
        assertEquals(AppRoute.Today, AppRoute.from("today", null))
        assertEquals(AppRoute.Today, AppRoute.from(null, null))
        assertEquals(AppRoute.Today, AppRoute.from("something-new", "x"))
    }

    @Test
    fun routesBelongToTheRightTab() {
        assertEquals(Tab.Cues, AppRoute.Cues(null).tab())
        assertEquals(Tab.Setup, AppRoute.Setup("x").tab())
        assertEquals(Tab.Today, AppRoute.ItemDetail("habit:a").tab())
        assertEquals(Tab.Today, AppRoute.Readiness.tab())
        assertEquals(Tab.Today, AppRoute.ContextSheet.tab())
    }
}
