package app.daycue.ui.setup

import app.daycue.domain.config.GeoPoint
import java.util.Locale

/*
 * Pure helpers behind the Setup screens (no Android, no Compose), so the rules the screens rely on are unit-tested
 * on the JVM: stepper steps, coordinate parsing, id generation, accuracy wording and the remote scope vocabulary.
 */

/** Same step sizes as the shared DurationField: 1 under 10, 5 under 60, 15 above. */
internal fun stepMinutes(value: Int, up: Boolean, min: Int, max: Int): Int {
    val base = if (up) value else value - 1
    val step = when {
        base < 10 -> 1
        base < 60 -> 5
        else -> 15
    }
    return (if (up) value + step else value - step).coerceIn(min, max)
}

/** Radius steps for a place circle: 25 m under 200, 50 m under 500, 100 m above. */
internal fun stepRadius(value: Int, up: Boolean, min: Int = PLACE_RADIUS_MIN, max: Int = PLACE_RADIUS_MAX): Int {
    val base = if (up) value else value - 1
    val step = when {
        base < 200 -> 25
        base < 500 -> 50
        else -> 100
    }
    val next = if (up) value + step else value - step
    return next.coerceIn(min, max)
}

/** Domain validation range for a place radius (PRODUCT 1.2). */
internal const val PLACE_RADIUS_MIN = 50
internal const val PLACE_RADIUS_MAX = 1000
internal const val PLACE_NAME_MAX = 40

internal sealed interface CoordinateParse {
    data class Ok(val point: GeoPoint) : CoordinateParse
    data object Empty : CoordinateParse
    data object BadLatitude : CoordinateParse
    data object BadLongitude : CoordinateParse
}

/** Accepts "10.0", "10,0" and surrounding spaces. Latitude -90..90, longitude -180..180. */
internal fun parseCoordinates(lat: String, lng: String): CoordinateParse {
    if (lat.isBlank() && lng.isBlank()) return CoordinateParse.Empty
    val la = number(lat)
    if (la == null || la < -90.0 || la > 90.0) return CoordinateParse.BadLatitude
    val lo = number(lng)
    if (lo == null || lo < -180.0 || lo > 180.0) return CoordinateParse.BadLongitude
    return CoordinateParse.Ok(GeoPoint(la, lo))
}

private fun number(text: String): Double? =
    text.trim().replace(',', '.').takeIf { it.isNotEmpty() }?.toDoubleOrNull()?.takeIf { it.isFinite() }

internal enum class NameProblem { Blank, TooLong }

internal fun nameProblem(name: String): NameProblem? = when {
    name.trim().isEmpty() -> NameProblem.Blank
    name.length > PLACE_NAME_MAX -> NameProblem.TooLong
    else -> null
}

/** A stable id for a new place: a slug of the name that is unique among [existing]. */
internal fun newPlaceId(name: String, existing: Set<String>): String {
    val slug = name.lowercase(Locale.ROOT).map { if (it.isLetterOrDigit() && it.code < 128) it else '-' }
        .joinToString("").replace(Regex("-+"), "-").trim('-').take(24).ifEmpty { "place" }
    var id = "place-$slug"
    var n = 2
    while (id in existing) { id = "place-$slug-$n"; n++ }
    return id
}

internal enum class AccuracyVerdict { Good, WiderThanCircle, TooCoarse }

/**
 * How trustworthy a "use current location" fix is for a circle of [radiusM]. A fix is too coarse when only the
 * approximate permission is granted or the reported accuracy is 500 m or worse; it is wider than the circle when
 * the accuracy is more than half the radius (the circle could then miss where you really are).
 */
internal fun judgeAccuracy(accuracyM: Float, radiusM: Int, precise: Boolean): AccuracyVerdict = when {
    !precise || accuracyM >= 500f || accuracyM.isNaN() -> AccuracyVerdict.TooCoarse
    accuracyM > radiusM / 2f -> AccuracyVerdict.WiderThanCircle
    else -> AccuracyVerdict.Good
}

/** Smallest radius from the standard steps that is at least [accuracyM] x 2, for the "use a larger circle" hint. */
internal fun suggestedRadius(accuracyM: Float): Int =
    (Math.ceil(accuracyM * 2.0 / 25.0).toInt() * 25).coerceIn(PLACE_RADIUS_MIN, PLACE_RADIUS_MAX)

// ---- remote access vocabulary ---------------------------------------------------------------------------

/** Scopes a connection can hold (RELAY.md 3.1) in the order they are listed to the owner. */
internal val KNOWN_SCOPES = listOf("config:read", "activity:read", "config:write", "sessions:control", "medication")

/** Scopes that stay switched off until the owner approves them on the phone. */
internal fun isGatedScope(scope: String): Boolean = scope == "config:write" || scope == "sessions:control" || scope == "medication"

/** Lead times as "10, 60" for display, largest first is not required: they are shown as the owner typed them. */
internal fun parseLeads(text: String): List<Int>? {
    val parts = text.split(',', ' ', ';').map { it.trim() }.filter { it.isNotEmpty() }
    if (parts.isEmpty()) return null
    val values = parts.map { it.toIntOrNull() ?: return null }
    if (values.any { it < 0 || it > 1440 }) return null
    return values.distinct()
}

internal fun parseKeywords(text: String): List<String> =
    text.split(',', '\n', ';').map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(20)
