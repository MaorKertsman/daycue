package app.daycue.ui.app

/**
 * Where a `daycue://open/<target>?item=<itemKey>&cue=<cueId>` link lands (UX 1.4, APP_API section 4). The stack is
 * always built synthetically: the tab root is Today, so Back from the target returns to Today.
 *
 * Cue, medication, routine, posture and alarm targets open the Cues tab with the item key as `startItem`; places,
 * calendar and remote targets open Setup; `item` opens the habit detail on Today with "Why now?" expanded;
 * `context` opens Today with the context sheet; `readiness` opens Reminder readiness.
 */
sealed interface AppRoute {
    data object Today : AppRoute
    data class ItemDetail(val itemKey: String) : AppRoute
    data object ContextSheet : AppRoute
    data object Readiness : AppRoute
    data class Cues(val startItem: String?) : AppRoute
    data class Setup(val startItem: String?) : AppRoute

    companion object {
        fun from(target: String?, item: String?): AppRoute = when (target) {
            "item" -> if (item.isNullOrBlank()) Today else ItemDetail(item)
            "dose", "medication", "routine", "posture", "alarm", "cue" -> Cues(item?.takeIf { it.isNotBlank() })
            "calendar", "places", "remote", "setup" -> Setup(item?.takeIf { it.isNotBlank() })
            "context" -> ContextSheet
            "readiness" -> Readiness
            else -> Today
        }
    }
}

enum class Tab { Today, Cues, Setup }

/** The tab a route belongs to. */
fun AppRoute.tab(): Tab = when (this) {
    is AppRoute.Cues -> Tab.Cues
    is AppRoute.Setup -> Tab.Setup
    else -> Tab.Today
}
