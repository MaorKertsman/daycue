package app.daycue.integrations.location

import com.google.android.gms.tasks.Task
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Minimal `Task.await()` (avoids adding kotlinx-coroutines-play-services for three call sites). */
internal suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { cont ->
    addOnCompleteListener { t ->
        if (!cont.isActive) return@addOnCompleteListener
        val e = t.exception
        when {
            e != null -> cont.resumeWithException(e)
            t.isCanceled -> cont.cancel()
            else -> @Suppress("UNCHECKED_CAST") cont.resume(t.result as T)
        }
    }
}
