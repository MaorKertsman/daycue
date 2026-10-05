package app.daycue.system

import app.daycue.domain.config.DayCueConfig
import app.daycue.domain.config.Defaults
import app.daycue.domain.config.Language
import app.daycue.domain.edit.ApplyResult
import app.daycue.domain.edit.ConfigEditor
import app.daycue.domain.edit.ConfigOp
import app.daycue.domain.edit.Preview
import app.daycue.domain.edit.Sensitivity
import app.daycue.engine.ApplyOutcome
import app.daycue.engine.HostLog
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** VALIDATION row 7a: config language stayed Hebrew under an English UI. */
class LanguageSyncTest {
    private class Fake(start: Language) {
        var cfg: DayCueConfig = Defaults.config(start)
        var perApp: String? = LanguagePolicy.tagOf(start)
        val events = mutableListOf<String>()
        /** Writes made by someone else just before our next apply (an engine op, an MCP op). */
        var interleave = 0
        val log = object : HostLog { override fun info(msg: String) {} ; override fun warn(msg: String, t: Throwable?) { events += "warn" } }

        suspend fun apply(ops: List<ConfigOp>, base: Long, source: String): ApplyOutcome {
            if (interleave > 0) { interleave--; cfg = cfg.copy(version = cfg.version + 1) } // concurrent writer bumps the version
            if (base != cfg.version) return ApplyOutcome.Conflict(cfg.version, base)
            cfg = (ConfigEditor.applyOps(cfg, ops, base) as ApplyResult.Applied).config
            events += "config:${cfg.settings.language}"
            return ApplyOutcome.Applied(cfg, Preview(emptyList(), Sensitivity.ordinary, emptyList()))
        }

        fun sync() = LanguageSync({ perApp }, { perApp = it; events += "ui:$it" }, { "en-US" }, { cfg }, ::apply, log)
    }

    @Test
    fun `set writes the config before switching the UI, and retries a version conflict`() = runBlocking {
        val f = Fake(Language.he)
        f.interleave = 2 // two concurrent writes race with ours
        f.sync().set("en", "ui")
        assertEquals(Language.en, f.cfg.settings.language)
        assertEquals("en", f.perApp)
        assertEquals(listOf("config:en", "ui:en"), f.events)
    }

    @Test
    fun `the system reconcile racing with set never leaves UI and config apart`() = runBlocking {
        val f = Fake(Language.he)
        val s = f.sync()
        val a = async { s.set("en", "ui") }
        val b = async { s.reconcile() } // onConfigurationChanged from the per-app switch
        a.await(); b.await()
        assertEquals(Language.en, f.cfg.settings.language)
        assertEquals("en", f.perApp)
    }

    @Test
    fun `a stale config emission does not move the UI back`() = runBlocking {
        val f = Fake(Language.he)
        val s = f.sync()
        s.set("en", "ui")
        s.followConfig() // the collector wakes up late for the old "he" emission: it re-reads the current config
        assertEquals("en", f.perApp)
    }
}
