package app.daycue

import android.app.Application

/**
 * Process entry point. Later: builds the app graph (database, clock, alarm scheduler),
 * creates notification channels once, and replays BootCompleted/TimeChanged into the engine
 * when the process starts (ARCHITECTURE.md §3.1).
 */
class DayCueApplication : Application()
