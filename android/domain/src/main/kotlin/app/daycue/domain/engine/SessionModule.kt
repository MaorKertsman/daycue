package app.daycue.domain.engine

import app.daycue.domain.config.CueType
import app.daycue.domain.context.Session
import app.daycue.domain.context.SessionNote
import app.daycue.domain.time.plusMin

/** Work/study session notifications (WRK-2) and history for session transitions (GEN-8). */
internal object SessionModule {

    private fun key(placeId: String) = "session:$placeId"

    fun onNote(run: Run, n: SessionNote) {
        when (n) {
            is SessionNote.AutoStarted -> {
                run.history("session", HistoryKind.SessionStarted, "WRK-2", detail = mapOf("place" to n.placeId, "auto" to "true"))
                notice(run, n.placeId, "cue.session.started", listOf(CueAction(ActionKind.NotWorking, Text("action.not_working"))), "WRK-2")
            }
            is SessionNote.Suggest -> notice(run, n.placeId, "cue.session.suggest",
                listOf(CueAction(ActionKind.Start, Text("action.start")), CueAction(ActionKind.NotNow, Text("action.not_now"))), "WRK-2", mapOf("kind" to n.kind.name))
            is SessionNote.Paused -> run.history("session", HistoryKind.SessionPaused, n.reason.substringBefore(' '), detail = mapOf("reason" to n.reason), at = n.at)
            is SessionNote.Resumed -> run.history("session", HistoryKind.Resumed, "WRK-3")
            is SessionNote.Ended -> {
                run.history("session", HistoryKind.SessionEnded, n.reason.substringBefore(' '), detail = mapOf("reason" to n.reason))
                run.st.delivery.visible.keys.filter { it.startsWith("session:") }.forEach { run.dismiss(it, null, "session_ended") }
            }
            is SessionNote.PlaceChanged -> run.history("context", HistoryKind.ContextChanged, "CTX-3", detail = mapOf("from" to n.from.toString(), "to" to n.to.toString()), at = n.at)
        }
    }

    private fun notice(run: Run, placeId: String, keyBase: String, actions: List<CueAction>, rule: String, args: Map<String, String> = emptyMap()) {
        val place = run.config.place(placeId)
        run.propose(Proposal(
            itemKey = key(placeId), notificationKey = key(placeId), type = CueType.Notice, dueAt = run.now, cueId = null,
            title = Text("$keyBase.title", args + mapOf("place" to (place?.name ?: placeId))), body = Text("$keyBase.body", args),
            actions = actions, why = WhyNow(rule, "why.session", if (run.ctxReady) run.ctx.facts() else emptyMap()), silent = true,
            commit = { st, _ -> st },
        ))
    }

    fun answer(run: Run, ev: Event.SessionPromptAnswer) {
        val cs = run.st.context
        val cooldown = run.config.contextRules.sessions.suggestCooldownMin
        val place = run.config.place(ev.placeId)
        run.dismiss(key(ev.placeId), ev.cueId, "answered")
        when (ev.answer) {
            SessionAnswer.Start -> if (cs.session == null) {
                val kind = place?.defaultSessionKind ?: app.daycue.domain.config.SessionKind.Working
                run.st = run.st.copy(context = cs.copy(session = Session(kind, ev.placeId, manual = false, startedAt = run.now, statusSince = run.now)))
                run.history("session", HistoryKind.SessionStarted, "WRK-2", ev.cueId, mapOf("place" to ev.placeId, "suggested" to "true"))
            }
            SessionAnswer.NotNow -> run.st = run.st.copy(context = cs.copy(sessionSuppressedUntil = cs.sessionSuppressedUntil + (ev.placeId to run.now.plusMin(cooldown))))
            SessionAnswer.NotWorking -> {
                run.st = run.st.copy(context = cs.copy(session = null, sessionSuppressedUntil = cs.sessionSuppressedUntil + (ev.placeId to run.now.plusMin(cooldown))))
                run.history("session", HistoryKind.SessionEnded, "WRK-2", ev.cueId, mapOf("reason" to "not_working"))
            }
        }
    }

    fun evaluate(@Suppress("UNUSED_PARAMETER") run: Run) {}
}
