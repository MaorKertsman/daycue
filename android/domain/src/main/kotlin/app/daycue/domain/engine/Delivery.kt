package app.daycue.domain.engine

import app.daycue.domain.config.SpeechInMeetingPolicy
import app.daycue.domain.time.plusSec

/**
 * Global delivery policy (PRODUCT §8): priority, collision grouping (COL-1..5), audible spacing, speech
 * in meetings (§8.5), alarm absorption (COL-4). Turns [Proposal]s into [Effect.Deliver]s and commits the
 * delivered ones.
 */
internal object Delivery {

    /** Returns true if anything was delivered. */
    fun deliver(run: Run): Boolean {
        val now = run.now
        val all = run.proposals.toList()
        run.proposals.clear()
        if (all.isEmpty()) return false
        val due = all.filter { !it.dueAt.isAfter(now) }
        if (due.isEmpty()) {
            all.forEach { run.wake(it.dueAt, WakePrecision.Exact, "due ${it.itemKey}") }
            return false
        }
        // COL-1: items due within mergeWindow join the group (pulled forward; only soft, pullable types).
        val group = (due + all.filter { it.dueAt.isAfter(now) && it.pullable })
            .sortedWith(compareBy({ it.type.priority }, { it.dueAt }))
        all.filter { it.dueAt.isAfter(now) && !it.pullable }.forEach { run.wake(it.dueAt, WakePrecision.Exact, "due ${it.itemKey}") }

        val c = run.config.settings.collision
        val hasHigh = group.any { it.type.priority <= 2 }
        val audible = group.filter { !it.silent && !it.isTest }
        // COL-3: keep audible deliveries minAudibleGap apart; delay (never drop). P1–P2 are never delayed.
        val gapEnd = run.st.delivery.lastAudibleAt?.plusSec(c.minAudibleGapSec)
        if (!hasHigh && audible.isNotEmpty() && gapEnd != null && now.isBefore(gapEnd)) {
            run.wake(gapEnd, WakePrecision.Exact, "COL-3 audible gap")
            return false
        }

        val ringing = run.st.delivery.ringingAlarmId != null
        val meeting = run.ctx.inMeeting
        val sp = run.config.settings.speech
        val lead = group.firstOrNull { !it.silent } ?: group.first()
        val groupKey = if (group.size > 1) "group-${run.newCueId("g").substringAfter('#')}" else null
        val language = run.config.settings.language

        // §8.5 meeting policy applies to the whole group (medication included: speech only, MED-3).
        val meetingNoSound = meeting && sp.inMeeting != SpeechInMeetingPolicy.SpeakAnyway
        val speakers = group.filter { !it.silent && it.speech != null && run.config.profileFor(it.type, it.profileId)?.speechEnabled != false }
        val speech = if (!sp.enabled || meetingNoSound || speakers.isEmpty()) null else {
            val first = speakers.first()
            val rest = speakers.drop(1)
            val max = c.maxSpokenItems
            val lang = run.config.profileFor(first.type, first.profileId)?.language ?: language
            SpeechRequest(
                language = lang,
                lead = first.speech!!,
                also = rest.take(max - 1).map { it.shortName },
                moreCount = (rest.size - (max - 1)).coerceAtLeast(0),
                overMedia = sp.overMedia, output = sp.output,
                createdAt = now, dropAfter = now.plusSec(c.speechMaxAgeSec),
            )
        }

        var anyAudible = false
        for (p in group) {
            val cueId = p.cueId ?: run.newCueId(p.itemKey)
            val isLead = p === lead
            val profile = run.config.profileFor(p.type, p.profileId)
            val silent = p.silent || !isLead || ringing
            val sound = if (silent || meetingNoSound) null else profile?.soundId?.takeIf { profile.soundEnabled }
            val vib = if (silent) null else profile?.vibrationId?.takeIf { profile.vibrationEnabled }
            val cueSpeech = if (isLead && !ringing && !p.silent) speech else null
            if (isLead && !p.silent && !p.isTest) anyAudible = true
            val cue = Cue(
                id = cueId, notificationKey = p.notificationKey, itemKey = p.itemKey, type = p.type, priority = p.type.priority,
                channelId = p.type.channelId, title = if (p.isTest) Text("cue.test.title", mapOf("inner" to p.title.key) + p.title.args) else p.title,
                body = p.body, actions = p.actions, lockScreen = p.lockScreen, publicTitle = p.publicTitle,
                soundId = sound, vibrationId = vib, silent = silent || (sound == null && vib == null),
                speech = cueSpeech, groupKey = groupKey, groupLead = isLead, repeatIndex = p.repeatIndex, isTest = p.isTest,
                ongoing = p.ongoing, fullScreen = p.fullScreen, why = p.why, dueAt = p.dueAt, deliveredAt = now,
            )
            run.effects += Effect.Deliver(cue)
            run.st = p.commit(run.st, cueId)
            run.st = run.st.copy(delivery = run.st.delivery.copy(visible = run.st.delivery.visible + (p.notificationKey to VisibleCue(cueId, p.notificationKey, p.itemKey, p.type, now, cue))))
            run.history(p.itemKey, if (p.repeatIndex > 0) HistoryKind.Repeated else HistoryKind.Delivered, p.why.rule, cueId,
                mapOf("silent" to cue.silent.toString(), "repeat" to p.repeatIndex.toString()) + (groupKey?.let { mapOf("group" to it) } ?: emptyMap()), test = p.isTest)
        }
        // COL-4: an alarm ringing absorbs speech; it's spoken after Stop/Snooze.
        if (ringing && speech != null) run.st = run.st.copy(delivery = run.st.delivery.copy(deferredSpeech = run.st.delivery.deferredSpeech + speech))
        if (anyAudible && !ringing) run.st = run.st.copy(delivery = run.st.delivery.copy(lastAudibleAt = now))
        return true
    }

    /** COL-4: flush absorbed speech after an alarm stops or snoozes (SPK-2 drops old utterances). */
    fun flushDeferredSpeech(run: Run) {
        val pending = run.st.delivery.deferredSpeech
        if (pending.isEmpty()) return
        val fresh = pending.filter { run.now.isBefore(it.dropAfter) }
        if (fresh.isNotEmpty()) {
            val first = fresh.first()
            val also = (first.also + fresh.drop(1).flatMap { listOf(it.lead) + it.also })
            val max = run.config.settings.collision.maxSpokenItems
            run.effects += Effect.Speak(first.copy(also = also.take(max - 1), moreCount = (also.size - (max - 1)).coerceAtLeast(0) + fresh.sumOf { it.moreCount }))
        }
        run.st = run.st.copy(delivery = run.st.delivery.copy(deferredSpeech = emptyList()))
    }
}
