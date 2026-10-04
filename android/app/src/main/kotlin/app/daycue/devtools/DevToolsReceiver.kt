package app.daycue.devtools

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import app.daycue.delivery.SpeechQueue
import app.daycue.delivery.SpeechUsage
import app.daycue.domain.config.DayCueJson
import app.daycue.domain.config.IntervalHabit
import app.daycue.domain.config.IntervalKind
import app.daycue.domain.config.Language
import app.daycue.domain.config.LocalizedText
import app.daycue.domain.config.Medication
import app.daycue.domain.config.MorningAlarm
import app.daycue.domain.config.RepeatPolicy
import app.daycue.domain.config.SpeechOutput
import app.daycue.domain.config.SpeechOverMediaPolicy
import app.daycue.domain.edit.ConfigOp
import app.daycue.domain.engine.EngineState
import app.daycue.domain.engine.Event
import app.daycue.domain.engine.RoutineTestMode
import app.daycue.domain.query.Queries
import app.daycue.domain.query.TodayView
import app.daycue.engine.ApplyOutcome
import app.daycue.facade.ConfigTransfer
import app.daycue.system.runAsync
import kotlinx.coroutines.delay
import kotlinx.serialization.builtins.ListSerializer
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Debug-only driver for the engine without UI (task step 8). Disabled in the manifest and enabled at
 * runtime only in debuggable builds ([DevTools.setEnabled]); it also requires the sender to hold
 * `android.permission.DUMP`, which `adb shell` has and ordinary apps don't. All output goes to logcat
 * tag `DayCueDebug`. Synthetic demo data only.
 *
 * ```
 * adb shell am broadcast -n app.daycue/.devtools.DevToolsReceiver -a app.daycue.devtools.CMD --es cmd dump
 * ... --es cmd demo --ei interval 5          interval habit "demo" every N (>= 5) min, quiet hours off
 * ... --es cmd demo_med --ei inMin 3         synthetic dose in N minutes
 * ... --es cmd demo_alarm --ei inMin 2       one-off alarm in N minutes
 * ... --es cmd ack --es habit demo           in-app ack (cueId = null)
 * ... --es cmd event --es json '{"type":"habitSnooze","habitId":"demo"}'
 * ... --es cmd ops --es json '[{"type":"setHabitInterval","id":"demo","minutes":5}]'
 * ... --es cmd speak --es lang he --es text 'שלום' [--es usage alarm]
 * ... --es cmd preview --es type Hydration   test reminder (GEN-10)
 * ... --es cmd voices | undo | export | tick | boot
 * ```
 */
class DevToolsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val cmd = intent.getStringExtra("cmd") ?: "dump"
        Log.i(TAG, "cmd=$cmd")
        runAsync(context, "devtools $cmd", timeoutMs = 9_500) { app ->
            val host = app.host
            val now = app.clock.now()
            val zone = app.clock.zone()
            when (cmd) {
                "dump" -> dump(app.host.ensureLoaded().let { it.config to it.state }, app)
                "tick" -> host.dispatch(Event.Tick)
                "boot" -> host.dispatch(Event.BootCompleted)
                "demo" -> {
                    val interval = intent.getIntExtra("interval", 5) // validation minimum for generic habits is 5 min
                    val habit = IntervalHabit(
                        id = "demo", kind = IntervalKind.Generic, name = "Demo habit", enabled = true, intervalMin = interval,
                        repeat = RepeatPolicy(everyMin = 5, maxRepeats = 1), snoozeMin = 5,
                        phrase = LocalizedText("Time for the demo habit", "הגיע הזמן להרגל לדוגמה"),
                    )
                    val cfg = host.ensureLoaded().config
                    report(host.applyOps(listOf(ConfigOp.UpsertHabit(habit), ConfigOp.SetQuietHours(cfg.settings.quietHours.copy(enabled = false))), cfg.version, "debug"))
                }
                "demo_med" -> {
                    val at = now.plus(intent.getIntExtra("inMin", 3).toLong(), ChronoUnit.MINUTES).atZone(zone).toLocalTime().truncatedTo(ChronoUnit.MINUTES)
                    val med = Medication("demo-med", "Demo dose", times = listOf(at), repeat = RepeatPolicy(5, 1), snoozeMin = 5)
                    val cfg = host.ensureLoaded().config
                    report(host.applyOps(listOf(ConfigOp.UpsertMedication(med)), cfg.version, "debug"))
                }
                "demo_alarm" -> {
                    val local = now.plus(intent.getIntExtra("inMin", 2).toLong(), ChronoUnit.MINUTES).atZone(zone)
                    val alarm = MorningAlarm("demo-alarm", "Demo alarm", enabled = true, time = local.toLocalTime().truncatedTo(ChronoUnit.MINUTES),
                        days = emptySet(), oneOffDate = local.toLocalDate(), volumeRampSec = 5, ringTimeoutMin = 2)
                    val cfg = host.ensureLoaded().config
                    report(host.applyOps(listOf(ConfigOp.UpsertAlarm(alarm)), cfg.version, "debug"))
                }
                "ack" -> host.dispatch(Event.HabitAck(intent.getStringExtra("habit") ?: "demo"))
                "event" -> host.dispatch(DayCueJson.decodeFromString(Event.serializer(), intent.getStringExtra("json") ?: return@runAsync))
                "ops" -> {
                    val ops = DayCueJson.decodeFromString(ListSerializer(ConfigOp.serializer()), intent.getStringExtra("json") ?: return@runAsync)
                    report(host.applyOps(ops, host.ensureLoaded().config.version, "debug"))
                }
                "undo" -> report(host.undo())
                // Needs an Activity of ours on screen (FGS start + while-in-use), e.g. after `am start -n app.daycue/.MainActivity`.
                "routine" -> {
                    val id = intent.getStringExtra("id") ?: "morning-routine"
                    val test = intent.getStringExtra("test")?.let { RoutineTestMode.valueOf(it) }
                    if (test != null) app.facade.testRoutine(context, id, test) else app.facade.startRoutine(context, id)
                }
                "preview" -> app.facade.sendTestReminder(cueType(intent.getStringExtra("type")))
                "speak" -> {
                    val lang = if (intent.getStringExtra("lang") == "he") Language.he else Language.en
                    val usage = if (intent.getStringExtra("usage") == "alarm") SpeechUsage.Alarm else SpeechUsage.Assistant
                    app.speech.enqueue(SpeechQueue.Item("debug:${now.toEpochMilli()}", 0, now, now.plusSeconds(60),
                        intent.getStringExtra("text") ?: "DayCue speech test", lang, SpeechOverMediaPolicy.DuckAndSpeak, SpeechOutput.AnyRoute, usage,
                        System.currentTimeMillis(), report = false))
                    app.speech.awaitIdle(8_000)
                    Log.i(TAG, "speak result: ${app.speech.lastResult}")
                }
                "voices" -> { app.speech.checkVoices(); delay(4_000); Log.i(TAG, "voices: ${app.speech.voices.value}") }
                "export" -> Log.i(TAG, ConfigTransfer.export(host.ensureLoaded().config, null, now))
                else -> Log.w(TAG, "unknown cmd $cmd")
            }
            if (cmd != "dump") dump(app.host.ensureLoaded().let { it.config to it.state }, app, brief = true)
        }
    }

    private fun report(r: ApplyOutcome) = Log.i(TAG, "apply: ${when (r) { is ApplyOutcome.Applied -> "applied v${r.config.version}"; else -> r.toString() }}")

    private fun dump(cs: Pair<app.daycue.domain.config.DayCueConfig, EngineState>, app: app.daycue.AppContainer, brief: Boolean = false) {
        val (cfg, st) = cs
        Log.i(TAG, "now=${Instant.now()} zone=${app.clock.zone()} config v${cfg.version} lang=${cfg.settings.language} quiet=${cfg.settings.quietHours.enabled}")
        Log.i(TAG, "nextWake=${st.nextWakeAt} ${st.nextWakePrecision} (${st.nextWakeReason}) lastArm=${app.host.lastArm} lastEval=${st.lastEvaluatedAt}")
        st.intervals.forEach { (id, s) -> Log.i(TAG, "interval $id lastAck=${s.lastAckAt} due=${s.dueAt} snoozed=${s.snoozedUntil} cue=${s.cue?.cueId} repeats=${s.cue?.repeatsDone}") }
        st.medication.slots.values.forEach { Log.i(TAG, "dose ${it.key} ${it.status} due=${it.dueAt} cue=${it.cue?.cueId}") }
        st.alarms.forEach { (id, a) -> Log.i(TAG, "alarm $id handled=${a.handledThrough} ring=${a.ring}") }
        Log.i(TAG, "visible=${st.delivery.visible.keys} readiness=${st.readiness} speech=${app.speech.lastResult}")
        if (!brief) {
            val view = Queries.todayView(cfg, st, app.clock)
            DayCueJson.encodeToString(TodayView.serializer(), view).chunked(3500).forEachIndexed { i, part -> Log.i(TAG, "today[$i] $part") }
        }
    }

    companion object { const val TAG = "DayCueDebug" }
}

private fun cueType(name: String?) =
    app.daycue.domain.config.CueType.entries.firstOrNull { it.name.equals(name, true) } ?: app.daycue.domain.config.CueType.Habit

/** Enables the debug receiver only in debuggable builds (it is `enabled="false"` in the manifest). */
object DevTools {
    fun setEnabled(context: Context, enabled: Boolean) {
        val cn = ComponentName(context, DevToolsReceiver::class.java)
        val want = if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        runCatching {
            if (context.packageManager.getComponentEnabledSetting(cn) != want) {
                context.packageManager.setComponentEnabledSetting(cn, want, PackageManager.DONT_KILL_APP)
            }
        }
    }
}
