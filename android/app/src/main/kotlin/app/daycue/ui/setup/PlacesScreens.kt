package app.daycue.ui.setup

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.daycue.R
import app.daycue.domain.config.AwayEnvironmentPolicy
import app.daycue.domain.config.Place
import app.daycue.domain.config.SessionKind
import app.daycue.domain.config.SessionStart
import app.daycue.domain.config.TypicalEnvironment
import app.daycue.domain.engine.OverrideDuration
import app.daycue.domain.time.TimeWindow
import app.daycue.facade.LocationFix
import app.daycue.integrations.location.CurrentLocationResult
import app.daycue.integrations.location.LocationDegradation
import app.daycue.integrations.location.LocationGrant
import app.daycue.integrations.location.LocationPermissionStep
import app.daycue.ui.components.ChoiceRow
import app.daycue.ui.components.DayCueBottomSheet
import app.daycue.ui.components.DayCueDialog
import app.daycue.ui.components.DayCueRow
import app.daycue.ui.components.DayCueTextButton
import app.daycue.ui.components.DayCueTextField
import app.daycue.ui.components.DayChips
import app.daycue.ui.components.DestructiveButton
import app.daycue.ui.components.Glyph
import app.daycue.ui.components.GlyphIcon
import app.daycue.ui.components.PolicyOption
import app.daycue.ui.components.PrimaryButton
import app.daycue.ui.components.SecondaryButton
import app.daycue.ui.components.SectionHeader
import app.daycue.ui.components.SettingRow
import app.daycue.ui.components.StateBlock
import app.daycue.ui.components.StateBlockKind
import app.daycue.ui.components.SwitchRow
import app.daycue.ui.components.TimeStepperPicker
import app.daycue.ui.components.TimeWindowField
import app.daycue.ui.components.StatusNotch
import app.daycue.ui.theme.DayCueTheme
import app.daycue.ui.util.durationDescription
import app.daycue.ui.util.durationText
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalTime

// ---- Places list ------------------------------------------------------------------------------------------

@Composable
fun PlacesScreen(onBack: () -> Unit, push: (String) -> Unit) {
    val vm: PlacesViewModel = viewModel()
    val ui by vm.ui.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshAccess() }
    var pauseSheet by remember { mutableStateOf(false) }
    val c = DayCueTheme.colors

    SetupFrame(stringResource(R.string.su_places_title), onBack) {
        Hint(stringResource(R.string.su_places_intro))
        val cfg = ui.config
        when {
            cfg == null -> repeat(3) { SkeletonRow(mark = true) }
            ui.places.isEmpty() -> {
                Gap(16)
                StateBlock(
                    StateBlockKind.Empty,
                    title = stringResource(R.string.su_places_empty_title),
                    body = stringResource(R.string.su_places_empty_body),
                    actionLabel = stringResource(R.string.su_place_add),
                    onAction = { push(SetupRoutes.PLACE + SetupRoutes.NEW) },
                )
            }
            else -> {
                ui.places.forEach { place ->
                    val here = place.id == ui.herePlaceId
                    val env = envWord(place.typicalEnvironment)
                    val detail = buildString {
                        if (here) append(stringResource(R.string.su_place_here)).append(" · ")
                        if (place.active) append(env).append(" · ").append(stringResource(R.string.su_radius_m, place.radiusM))
                        else append(stringResource(R.string.su_place_not_set))
                    }
                    DayCueRow(
                        primary = place.name,
                        secondary = detail,
                        leading = { PlaneSquare(place.typicalEnvironment, place.active) },
                        trailing = { GlyphIcon(Glyph.Chevron, c.ink2) },
                        onClick = { push(SetupRoutes.withName(SetupRoutes.PLACE, place.id, place.name)) },
                    )
                }
                // "Add" in a populated list is a text button at the end of the list (REVIEW-2 S4).
                DayCueTextButton(stringResource(R.string.su_place_add), { push(SetupRoutes.PLACE + SetupRoutes.NEW) })
            }
        }

        SectionHeader(stringResource(R.string.su_location_access_header))
        LocationAccessSection(vm, ui.access, ui.places.any { it.active })

        SectionHeader(stringResource(R.string.su_context_header))
        if (ui.detectionPaused) {
            DayCueRow(
                primary = stringResource(R.string.su_detection_paused),
                secondary = stringResource(R.string.su_detection_paused_hint),
                trailing = { DayCueTextButton(stringResource(R.string.su_resume), { vm.resumeDetection() }) },
            )
        } else {
            SettingRow(
                stringResource(R.string.su_pause_detection),
                stringResource(R.string.su_pause_detection_hint),
                { pauseSheet = true },
            )
        }
        SettingRow(stringResource(R.string.su_context_settings), stringResource(R.string.su_context_settings_hint), { push(SetupRoutes.CONTEXT) })
    }

    if (pauseSheet) {
        val labels = pauseOptions()
        DayCueBottomSheet(onDismiss = { pauseSheet = false }, title = stringResource(R.string.su_pause_detection)) {
            Hint(stringResource(R.string.su_pause_detection_explain))
            Gap(8)
            labels.forEach { (label, duration) ->
                DayCueRow(primary = label, onClick = { vm.pauseDetection(duration); pauseSheet = false })
            }
        }
    }
}

@Composable
private fun pauseOptions(): List<Pair<String, OverrideDuration>> = listOf(
    durationText(30) to OverrideDuration.For(30),
    durationText(60) to OverrideDuration.For(60),
    durationText(120) to OverrideDuration.For(120),
    durationText(240) to OverrideDuration.For(240),
    stringResource(R.string.su_pause_rest_of_day) to OverrideDuration.RestOfToday,
    stringResource(R.string.su_pause_until_resumed) to OverrideDuration.UntilChanged,
)

/**
 * A placeholder row. It stays invisible for the first 150ms so local data that arrives on the next frame never flashes a
 * skeleton (UX 2), and it has a mark slot only where the real rows do (REVIEW-2 C13).
 */
@Composable
internal fun SkeletonRow(mark: Boolean = false) {
    val c = DayCueTheme.colors
    var show by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(Unit) { kotlinx.coroutines.delay(150); show = true }
    Box(Modifier.fillMaxWidth().height(64.dp)) {
        if (show) Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            if (mark) {
                Box(Modifier.size(28.dp).background(c.sunk, RoundedCornerShape(6.dp)))
                Box(Modifier.padding(start = 16.dp).weight(1f).height(16.dp).background(c.sunk, RoundedCornerShape(4.dp)))
            } else {
                Box(Modifier.weight(1f).height(16.dp).background(c.sunk, RoundedCornerShape(4.dp)))
            }
        }
    }
}

/** The place's tone square; a place with no location yet is an outlined, hatched "unknown" square (REVIEW-2 C17). */
@Composable
private fun PlaneSquare(env: TypicalEnvironment, active: Boolean) {
    val c = DayCueTheme.colors
    val tone = when (env) {
        TypicalEnvironment.Indoor -> c.planeHome
        TypicalEnvironment.Outdoor -> c.planeOutdoors
        TypicalEnvironment.Mixed -> c.planeWork
    }
    val shape = RoundedCornerShape(6.dp)
    if (active) {
        Box(Modifier.size(28.dp).background(tone, shape))
    } else {
        val line = c.ink2
        Box(
            Modifier
                .size(28.dp)
                .clip(shape)
                .background(c.paper)
                .drawBehind {
                    val step = 7.dp.toPx()
                    var x = -size.height
                    while (x < size.width) {
                        drawLine(line.copy(alpha = 0.45f), androidx.compose.ui.geometry.Offset(x, size.height), androidx.compose.ui.geometry.Offset(x + size.height, 0f), strokeWidth = 1.dp.toPx())
                        x += step
                    }
                }
                .border(1.dp, line, shape),
        )
    }
}

@Composable
internal fun envWord(env: TypicalEnvironment): String = stringResource(
    when (env) {
        TypicalEnvironment.Indoor -> R.string.su_env_indoor
        TypicalEnvironment.Outdoor -> R.string.su_env_outdoor
        TypicalEnvironment.Mixed -> R.string.su_env_mixed
    },
)

// ---- Progressive location permission flow -----------------------------------------------------------------

/**
 * Foreground first, then precise, then background as its own explained step (Android rejects a combined request).
 * Always says in words what stops working without each permission (APP_API 12, FEASIBILITY 5.2).
 */
@Composable
fun LocationAccessSection(vm: PlacesViewModel, access: app.daycue.integrations.location.LocationAccessState?, hasActivePlaces: Boolean) {
    val context = LocalContext.current
    val c = DayCueTheme.colors
    var denied by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.any { !it }) denied = true
        vm.onPermissionsChanged()
    }
    if (access == null) { SkeletonRow(); return }

    val step = access.nextStep
    when (step) {
        LocationPermissionStep.Foreground, LocationPermissionStep.Precise -> {
            val approximate = step == LocationPermissionStep.Precise
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Text(
                    stringResource(if (approximate) R.string.su_perm_precise_title else R.string.su_perm_fg_title),
                    style = DayCueTheme.type.titleSmall, color = c.ink,
                )
                Hint(stringResource(R.string.su_perm_off_line))
                Gap(4)
                // The places screen's primary is "Add place"; allowing location is the secondary action here.
                SecondaryButton(
                    stringResource(R.string.su_perm_allow),
                    { launcher.launch(vm.nextPermissionPermissions().toTypedArray()) },
                    Modifier.fillMaxWidth(),
                )
                if (denied) {
                    FieldNote(stringResource(R.string.su_perm_denied_hint), error = false)
                    DayCueTextButton(stringResource(R.string.su_open_app_settings), { context.startActivitySafely(vm.fixIntent(LocationFix.AppSettings)) })
                }
                LearnMore(stringResource(if (approximate) R.string.su_perm_precise_why else R.string.su_perm_fg_why) + "\n\n" + stringResource(R.string.su_perm_fg_without))
            }
        }
        LocationPermissionStep.Background -> {
            val option = vm.backgroundOptionLabel()
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Text(stringResource(R.string.su_perm_bg_title), style = DayCueTheme.type.titleSmall, color = c.ink)
                Hint(stringResource(R.string.su_perm_bg_without))
                Gap(4)
                SecondaryButton(
                    stringResource(R.string.su_perm_bg_allow),
                    { launcher.launch(vm.nextPermissionPermissions().toTypedArray()) },
                    Modifier.fillMaxWidth(),
                )
                DayCueTextButton(stringResource(R.string.su_open_app_settings), { context.startActivitySafely(vm.fixIntent(LocationFix.AppSettings)) })
                LearnMore(stringResource(R.string.su_perm_bg_why) + (if (option != null) "\n\n" + stringResource(R.string.su_perm_bg_how, option) else ""))
            }
        }
        LocationPermissionStep.Done -> {
            DayCueRow(
                primary = stringResource(R.string.su_perm_done_title),
                leading = { GlyphIcon(Glyph.Check, c.ink) },
            )
        }
    }

    // What degrades right now is already said above; only the fix actions remain (REVIEW-2 C8: no second list).
    val notes = access.degradations.filter { it != LocationDegradation.NoActivityRecognition }
    if (LocationDegradation.LocationServicesOff in notes) {
        DayCueTextButton(stringResource(R.string.su_open_location_settings), { context.startActivitySafely(vm.fixIntent(LocationFix.LocationSettings)) })
    }
    if (LocationDegradation.NoPlayServices in notes) {
        DayCueTextButton(stringResource(R.string.su_open_play_services), { context.startActivitySafely(vm.fixIntent(LocationFix.PlayServices)) })
    }

    // Optional: physical activity (on-foot = outdoors away from saved places).
    if (vm.activityPermissions().isNotEmpty()) {
        if (access.activityRecognition) {
            DayCueRow(
                primary = stringResource(R.string.su_perm_activity_on),
                leading = { GlyphIcon(Glyph.Check, c.ink) },
            )
        } else {
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Text(stringResource(R.string.su_perm_activity_title), style = DayCueTheme.type.titleSmall, color = c.ink)
                Hint(stringResource(R.string.su_perm_activity_why))
                DayCueTextButton(stringResource(R.string.su_perm_allow), { launcher.launch(vm.activityPermissions().toTypedArray()) })
            }
        }
    }
}

private fun degradationText(d: LocationDegradation): Int = when (d) {
    LocationDegradation.NoPlayServices -> R.string.su_deg_no_play
    LocationDegradation.NoLocationPermission -> R.string.su_deg_no_permission
    LocationDegradation.ApproximateOnly -> R.string.su_deg_approx
    LocationDegradation.ForegroundOnly -> R.string.su_deg_foreground
    LocationDegradation.LocationServicesOff -> R.string.su_deg_location_off
    LocationDegradation.NoActivityRecognition -> R.string.su_deg_no_activity
}

// ---- Place editor ------------------------------------------------------------------------------------------

@Composable
fun PlaceEditorScreen(placeId: String, onBack: () -> Unit, nameHint: String? = null) {
    val vm: PlacesViewModel = viewModel()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val fixState by vm.lastFix.collectAsStateWithLifecycle()
    val locating by vm.locating.collectAsStateWithLifecycle()
    val isNew = placeId == SetupRoutes.NEW
    val cfg = ui.config
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val c = DayCueTheme.colors

    DisposableClearFix(vm)
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshAccess() }

    if (cfg == null) {
        SetupFrame(if (isNew) stringResource(R.string.su_place_new) else nameHint ?: stringResource(R.string.su_place_title), onBack) { repeat(4) { SkeletonRow() } }
        return
    }
    val existing = if (isNew) null else ui.places.firstOrNull { it.id == placeId }
    if (!isNew && existing == null) {
        SetupFrame(stringResource(R.string.su_place_title), onBack) {
            StateBlock(StateBlockKind.Error, stringResource(R.string.su_place_missing), body = stringResource(R.string.su_place_missing_body), actionLabel = stringResource(R.string.su_back), onAction = onBack)
        }
        return
    }

    var draftNew by remember { mutableStateOf(Place(id = newPlaceId("", ui.places.map { it.id }.toSet()), name = "")) }
    val place = existing ?: draftNew
    var name by rememberSaveable(placeId) { mutableStateOf(existing?.name ?: "") }
    var saveErrorRes by remember { mutableStateOf<Int?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var latText by rememberSaveable { mutableStateOf("") }
    var lngText by rememberSaveable { mutableStateOf("") }
    var coordError by remember { mutableStateOf<Int?>(null) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var sessionSheet by remember { mutableStateOf(false) }
    var envSheet by remember { mutableStateOf(false) }

    fun update(change: (Place) -> Place) {
        if (isNew) draftNew = change(draftNew)
        else existing?.let { base ->
            scope.launch {
                val r = vm.savePlace(change(base))
                saveErrorRes = if (r is EditResult.Invalid) friendlyError(r.errors.first()) else null
            }
        }
    }

    // Existing places save the name a moment after typing stops (each change is one op, no Save button).
    LaunchedEffect(name) {
        if (!isNew && existing != null && name != existing.name && nameProblem(name) == null) {
            kotlinx.coroutines.delay(700)
            vm.savePlace(existing.copy(name = name.trim()))
        }
    }

    // A fresh "use current location" fix becomes the place's center.
    LaunchedEffect(fixState) {
        val fix = fixState as? CurrentLocationResult.Ok ?: return@LaunchedEffect
        if (fix.precise) {
            val point = app.daycue.domain.config.GeoPoint(fix.fix.lat, fix.fix.lng)
            if (isNew) draftNew = draftNew.copy(center = point)
            else existing?.let { vm.setLocation(it.id, point) }
        }
    }

    val nameIssue = nameProblem(name).takeIf { name.isNotEmpty() || saveErrorRes != null }

    SetupFrame(if (isNew) stringResource(R.string.su_place_new) else existing?.name?.takeIf { it.isNotBlank() } ?: stringResource(R.string.su_place_title), onBack) {
        // ---- Simple tier: name, where, radius, indoors or outdoors, work here.
        DayCueTextField(
            value = name,
            onValueChange = { name = it; if (isNew) draftNew = draftNew.copy(name = it.trim()) },
            label = stringResource(R.string.su_place_name),
            error = when (nameIssue) {
                NameProblem.Blank -> stringResource(R.string.su_err_name_blank)
                NameProblem.TooLong -> stringResource(R.string.su_err_name_long, PLACE_NAME_MAX)
                null -> null
            },
        )
        Gap(8)
        val useCurrent: @Composable (Modifier) -> Unit = { m ->
            val label = stringResource(if (locating) R.string.su_locating else R.string.su_use_current)
            // One primary per screen: on a new place "Add place" is it; on a saved place the primary is the missing location.
            if (!isNew && !place.active) PrimaryButton(label, { vm.useCurrentLocation() }, m, enabled = !locating)
            else SecondaryButton(label, { vm.useCurrentLocation() }, m, enabled = !locating)
        }
        useCurrent(Modifier.fillMaxWidth())
        Gap(4)
        Text(
            stringResource(if (place.active) R.string.su_loc_saved else R.string.su_loc_not_set),
            style = DayCueTheme.type.bodySmall, color = c.ink2,
        )
        FixResult(fixState, place.radiusM, ui.access?.nextStep, vm)

        StepperRow(
            label = stringResource(R.string.su_radius_label),
            valueText = stringResource(R.string.su_radius_m, place.radiusM),
            valueDescription = pluralStringResource(R.plurals.su_desc_meters, place.radiusM, place.radiusM),
            onDecrease = { update { it.copy(radiusM = stepRadius(it.radiusM, false)) } },
            onIncrease = { update { it.copy(radiusM = stepRadius(it.radiusM, true)) } },
            canDecrease = place.radiusM > PLACE_RADIUS_MIN,
            canIncrease = place.radiusM < PLACE_RADIUS_MAX,
        )
        SettingRow(stringResource(R.string.su_env_label), envWord(place.typicalEnvironment), { envSheet = true })
        SwitchRow(
            stringResource(R.string.su_work_here), SessionKind.Working in place.allowedActivities,
            { on -> update { it.copy(allowedActivities = if (on) it.allowedActivities + SessionKind.Working else it.allowedActivities - SessionKind.Working) } },
        )

        // ---- More options: coordinates, studying, session start, bottle, routines.
        MoreOptionsRow(advanced, { advanced = !advanced })
        if (advanced) {
            Gap(8)
            Text(stringResource(R.string.su_coords_title), style = DayCueTheme.type.titleSmall, color = c.ink)
            Hint(stringResource(R.string.su_coords_hint))
            DayCueTextField(
                latText, { latText = it; coordError = null }, stringResource(R.string.su_coords_lat),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
            Gap(8)
            DayCueTextField(
                lngText, { lngText = it; coordError = null }, stringResource(R.string.su_coords_lng),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Decimal),
                error = coordError?.let { stringResource(it) },
            )
            DayCueTextButton(stringResource(R.string.su_coords_set), {
                when (val parsed = parseCoordinates(latText, lngText)) {
                    is CoordinateParse.Ok -> {
                        coordError = null
                        if (isNew) draftNew = draftNew.copy(center = parsed.point)
                        else existing?.let { scope.launch { vm.setLocation(it.id, parsed.point) } }
                        latText = ""; lngText = ""
                    }
                    CoordinateParse.Empty -> coordError = R.string.su_coords_empty
                    CoordinateParse.BadLatitude -> coordError = R.string.su_coords_bad_lat
                    CoordinateParse.BadLongitude -> coordError = R.string.su_coords_bad_lng
                }
            })
            Hint(stringResource(R.string.su_map_not_included))

            Gap(8)
            SwitchRow(
                stringResource(R.string.su_allow_studying), SessionKind.Studying in place.allowedActivities,
                { on -> update { it.copy(allowedActivities = if (on) it.allowedActivities + SessionKind.Studying else it.allowedActivities - SessionKind.Studying) } },
            )
            SettingRow(stringResource(R.string.su_session_start), sessionStartWord(place.sessionStart), { sessionSheet = true })
            SwitchRow(
                stringResource(R.string.su_bottle_on_leave), place.bottleReminderOnLeave,
                { on -> update { it.copy(bottleReminderOnLeave = on) } },
                secondary = stringResource(R.string.su_bottle_on_leave_hint),
            )
            val routines = cfg.routines
            if (routines.isNotEmpty()) {
                Gap(8)
                Text(stringResource(R.string.su_routines_here), style = DayCueTheme.type.titleSmall, color = c.ink)
                routines.forEach { r ->
                    SwitchRow(
                        r.name, r.id in place.allowedRoutines,
                        { on -> update { it.copy(allowedRoutines = if (on) it.allowedRoutines + r.id else it.allowedRoutines - r.id) } },
                    )
                }
            }
            if (SessionKind.Working in place.allowedActivities && SessionKind.Studying in place.allowedActivities) {
                SettingRow(
                    stringResource(R.string.su_default_kind),
                    stringResource(if (place.defaultSessionKind == SessionKind.Working) R.string.su_kind_working else R.string.su_kind_studying),
                    { update { it.copy(defaultSessionKind = if (it.defaultSessionKind == SessionKind.Working) SessionKind.Studying else SessionKind.Working) } },
                )
            }
        }

        saveErrorRes?.let { FieldNote(stringResource(it)) }

        Gap(16)
        if (isNew) {
            PrimaryButton(
                stringResource(R.string.su_place_add),
                {
                    val problem = nameProblem(name)
                    if (problem != null) {
                        saveErrorRes = if (problem == NameProblem.Blank) R.string.su_err_name_blank else R.string.su_err_length
                    } else scope.launch {
                        val id = newPlaceId(name, ui.places.map { it.id }.toSet())
                        val r = vm.savePlace(draftNew.copy(id = id, name = name.trim()), announce = true)
                        if (r is EditResult.Applied) onBack() else if (r is EditResult.Invalid) saveErrorRes = friendlyError(r.errors.first())
                    }
                },
                Modifier.fillMaxWidth(),
            )
            if (!place.active) Hint(stringResource(R.string.su_place_add_inactive_hint))
        } else {
            DayCueTextButton(stringResource(R.string.su_place_delete), { confirmDelete = true }, color = c.error.ink)
        }
    }

    if (confirmDelete && existing != null) {
        DayCueDialog(
            title = stringResource(R.string.su_place_delete_title, existing.name),
            text = stringResource(R.string.su_place_delete_body),
            confirmLabel = stringResource(R.string.su_delete),
            dismissLabel = stringResource(R.string.su_cancel),
            onConfirm = { confirmDelete = false; vm.deletePlace(existing.id); onBack() },
            onDismiss = { confirmDelete = false },
            destructive = true,
        )
    }
    if (envSheet) {
        val opts = listOf(TypicalEnvironment.Indoor, TypicalEnvironment.Outdoor, TypicalEnvironment.Mixed)
        ChoiceSheet(
            stringResource(R.string.su_env_label),
            opts.map { PolicyOption(envLabel(it), envConsequence(it)) },
            opts.indexOf(place.typicalEnvironment),
            { update { p -> p.copy(typicalEnvironment = opts[it]) } },
            { envSheet = false },
        )
    }
    if (sessionSheet) {
        val opts = listOf(SessionStart.AutoStart, SessionStart.Suggest, SessionStart.Off)
        ChoiceSheet(
            stringResource(R.string.su_session_start),
            opts.map { PolicyOption(sessionStartWord(it), sessionStartConsequence(it)) },
            opts.indexOf(place.sessionStart),
            { update { p -> p.copy(sessionStart = opts[it]) } },
            { sessionSheet = false },
        )
    }
}

@Composable
private fun DisposableClearFix(vm: PlacesViewModel) {
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { vm.clearFix() } }
}

@Composable
private fun envLabel(e: TypicalEnvironment) = envWord(e)

@Composable
private fun envConsequence(e: TypicalEnvironment) = stringResource(
    when (e) {
        TypicalEnvironment.Indoor -> R.string.su_env_indoor_hint
        TypicalEnvironment.Outdoor -> R.string.su_env_outdoor_hint
        TypicalEnvironment.Mixed -> R.string.su_env_mixed_hint
    },
)

@Composable
internal fun sessionStartWord(s: SessionStart) = stringResource(
    when (s) {
        SessionStart.AutoStart -> R.string.su_start_auto
        SessionStart.Suggest -> R.string.su_start_suggest
        SessionStart.Off -> R.string.su_start_off
    },
)

@Composable
private fun sessionStartConsequence(s: SessionStart) = stringResource(
    when (s) {
        SessionStart.AutoStart -> R.string.su_start_auto_hint
        SessionStart.Suggest -> R.string.su_start_suggest_hint
        SessionStart.Off -> R.string.su_start_off_hint
    },
)

/** Outcome of "Use my current location": accuracy in words, never raw coordinates. */
@Composable
private fun FixResult(state: CurrentLocationResult?, radiusM: Int, nextStep: LocationPermissionStep?, vm: PlacesViewModel) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { vm.onPermissionsChanged() }
    when (state) {
        null -> Unit
        is CurrentLocationResult.Ok -> {
            val meters = Math.round(state.fix.accuracyM).coerceAtLeast(1)
            when (judgeAccuracy(state.fix.accuracyM, radiusM, state.precise)) {
                AccuracyVerdict.Good -> FieldNote(stringResource(R.string.su_fix_good, meters), error = false)
                AccuracyVerdict.WiderThanCircle -> FieldNote(stringResource(R.string.su_fix_wide, meters, radiusM, suggestedRadius(state.fix.accuracyM)), error = false)
                AccuracyVerdict.TooCoarse -> {
                    FieldNote(stringResource(R.string.su_fix_coarse, meters))
                    if (!state.precise) {
                        DayCueTextButton(
                            stringResource(R.string.su_perm_allow_precise),
                            { launcher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) },
                        )
                    }
                }
            }
        }
        CurrentLocationResult.NoPermission -> {
            FieldNote(stringResource(R.string.su_fix_no_permission))
            DayCueTextButton(
                stringResource(R.string.su_perm_allow),
                { launcher.launch(vm.nextPermissionPermissions().toTypedArray()) },
            )
        }
        CurrentLocationResult.LocationOff -> {
            FieldNote(stringResource(R.string.su_fix_location_off))
            DayCueTextButton(stringResource(R.string.su_open_location_settings), { context.startActivitySafely(vm.fixIntent(LocationFix.LocationSettings)) })
        }
        CurrentLocationResult.PlayServicesMissing -> FieldNote(stringResource(R.string.su_fix_no_play))
        CurrentLocationResult.Unavailable -> FieldNote(stringResource(R.string.su_fix_unavailable))
        is CurrentLocationResult.Failed -> FieldNote(stringResource(R.string.su_fix_failed))
    }
}

// ---- Context and session settings ----------------------------------------------------------------------------

@Composable
fun ContextSettingsScreen(onBack: () -> Unit) {
    val vm: PlacesViewModel = viewModel()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val cfg = ui.config
    var awaySheet by remember { mutableStateOf(false) }
    var pickFrom by remember { mutableStateOf(false) }
    var pickTo by remember { mutableStateOf(false) }
    var errorRes by remember { mutableStateOf<Int?>(null) }

    SetupFrame(stringResource(R.string.su_ctx_title), onBack) {
        if (cfg == null) { repeat(4) { SkeletonRow() }; return@SetupFrame }
        val rules = cfg.contextRules
        val s = rules.sessions
        fun saveSession(change: (app.daycue.domain.config.SessionRules) -> app.daycue.domain.config.SessionRules) {
            scope.launch {
                val r = vm.saveSessionRules(change(s))
                errorRes = if (r is EditResult.Invalid) friendlyError(r.errors.first()) else null
            }
        }
        fun saveContext(change: (app.daycue.domain.config.ContextRules) -> app.daycue.domain.config.ContextRules) {
            scope.launch {
                val r = vm.saveContextRules(change(rules))
                errorRes = if (r is EditResult.Invalid) friendlyError(r.errors.first()) else null
            }
        }
        SectionHeader(stringResource(R.string.su_ctx_sessions_header), topPadding = 8.dp)
        MinutesStepper(R.string.su_ctx_sustained, s.sustainedActiveToStartMin, 1, 30, R.string.su_ctx_sustained_hint) { v -> saveSession { it.copy(sustainedActiveToStartMin = v) } }
        MinutesStepper(R.string.su_ctx_idle, s.idleToPauseMin, 2, 60, R.string.su_ctx_idle_hint) { v -> saveSession { it.copy(idleToPauseMin = v) } }
        MinutesStepper(R.string.su_ctx_locked, s.lockedToPauseMin, 0, 30, R.string.su_ctx_locked_hint) { v -> saveSession { it.copy(lockedToPauseMin = v) } }
        MinutesStepper(R.string.su_ctx_paused_end, s.pausedToEndMin, 15, 240, R.string.su_ctx_paused_end_hint) { v -> saveSession { it.copy(pausedToEndMin = v) } }
        Gap(8)
        Text(stringResource(R.string.su_ctx_hours), style = DayCueTheme.type.titleSmall, color = DayCueTheme.colors.ink)
        Hint(stringResource(R.string.su_ctx_hours_hint))
        TimeWindowField(
            s.permittedHours.start.hour * 60 + s.permittedHours.start.minute,
            s.permittedHours.end.hour * 60 + s.permittedHours.end.minute,
            { pickFrom = true }, { pickTo = true },
        )
        val customDays = s.permittedDays != null
        SwitchRow(
            stringResource(R.string.su_ctx_own_days), customDays,
            { on -> saveSession { it.copy(permittedDays = if (on) cfg.settings.workDays else null) } },
            secondary = stringResource(R.string.su_ctx_own_days_hint),
        )
        if (customDays) {
            Gap(4)
            DayChips(s.permittedDays.orEmpty(), { day ->
                val current = s.permittedDays.orEmpty()
                val next = if (day in current) current - day else current + day
                if (next.isNotEmpty()) saveSession { it.copy(permittedDays = next) }
            })
        }
        SwitchRow(
            stringResource(R.string.su_ctx_meeting_keeps), s.meetingKeepsSessionActive,
            { on -> saveSession { it.copy(meetingKeepsSessionActive = on) } },
            secondary = stringResource(R.string.su_ctx_meeting_keeps_hint),
        )

        SectionHeader(stringResource(R.string.su_ctx_places_header))
        MinutesStepper(R.string.su_ctx_enter, rules.placeEnterDwellMin, 1, 15, R.string.su_ctx_enter_hint) { v -> saveContext { it.copy(placeEnterDwellMin = v) } }
        MinutesStepper(R.string.su_ctx_exit, rules.placeExitDwellMin, 0, 20, R.string.su_ctx_exit_hint) { v -> saveContext { it.copy(placeExitDwellMin = v) } }
        MinutesStepper(R.string.su_ctx_outdoor_enter, rules.outdoorEnterDwellMin, 0, 20, R.string.su_ctx_outdoor_enter_hint) { v -> saveContext { it.copy(outdoorEnterDwellMin = v) } }
        MinutesStepper(R.string.su_ctx_outdoor_exit, rules.outdoorExitDwellMin, 0, 60, R.string.su_ctx_outdoor_exit_hint) { v -> saveContext { it.copy(outdoorExitDwellMin = v) } }
        SettingRow(stringResource(R.string.su_ctx_away), awayWord(rules.awayEnvironment), { awaySheet = true })
        errorRes?.let { FieldNote(stringResource(it)) }
    }

    if (cfg != null) {
        val s = cfg.contextRules.sessions
        if (pickFrom || pickTo) {
            val editingFrom = pickFrom
            val current = if (editingFrom) s.permittedHours.start else s.permittedHours.end
            var minutes by remember(editingFrom) { mutableStateOf(current.hour * 60 + current.minute) }
            DayCueBottomSheet(
                onDismiss = { pickFrom = false; pickTo = false },
                title = stringResource(if (editingFrom) R.string.su_ctx_hours_from else R.string.su_ctx_hours_to),
                primaryLabel = stringResource(R.string.su_done),
                onPrimary = {
                    val t = LocalTime.of(minutes / 60, minutes % 60)
                    val window = if (editingFrom) TimeWindow(t, s.permittedHours.end) else TimeWindow(s.permittedHours.start, t)
                    scope.launch { vm.saveSessionRules(s.copy(permittedHours = window)) }
                    pickFrom = false; pickTo = false
                },
            ) { TimeStepperPicker(minutes, { minutes = it }) }
        }
        if (awaySheet) {
            val opts = listOf(AwayEnvironmentPolicy.OutdoorWhenOnFoot, AwayEnvironmentPolicy.Unknown, AwayEnvironmentPolicy.AssumeOutdoor)
            ChoiceSheet(
                stringResource(R.string.su_ctx_away),
                opts.map { PolicyOption(awayWord(it), stringResource(awayHint(it))) },
                opts.indexOf(cfg.contextRules.awayEnvironment),
                { i -> scope.launch { vm.saveContextRules(cfg.contextRules.copy(awayEnvironment = opts[i])) } },
                { awaySheet = false },
            )
        }
    }
}

@Composable
private fun awayWord(p: AwayEnvironmentPolicy) = stringResource(
    when (p) {
        AwayEnvironmentPolicy.OutdoorWhenOnFoot -> R.string.su_away_foot
        AwayEnvironmentPolicy.Unknown -> R.string.su_away_unknown
        AwayEnvironmentPolicy.AssumeOutdoor -> R.string.su_away_assume
    },
)

private fun awayHint(p: AwayEnvironmentPolicy): Int = when (p) {
    AwayEnvironmentPolicy.OutdoorWhenOnFoot -> R.string.su_away_foot_hint
    AwayEnvironmentPolicy.Unknown -> R.string.su_away_unknown_hint
    AwayEnvironmentPolicy.AssumeOutdoor -> R.string.su_away_assume_hint
}

/** Minutes with steppers (1 under 10, 5 under 60, 15 above) and a consequence sentence. */
@Composable
internal fun MinutesStepper(
    @androidx.annotation.StringRes label: Int,
    value: Int,
    min: Int,
    max: Int,
    @androidx.annotation.StringRes hint: Int?,
    onChange: (Int) -> Unit,
) {
    StepperRow(
        label = stringResource(label),
        valueText = durationText(value),
        valueDescription = durationDescription(value),
        onDecrease = { onChange(stepMinutes(value, false, min, max)) },
        onIncrease = { onChange(stepMinutes(value, true, min, max)) },
        canDecrease = value > min,
        canIncrease = value < max,
        hint = hint?.let { stringResource(it) },
    )
}
