# ADR-0004 Calendar via the Android Calendar Provider (read-only)

- Status: accepted (decision by the delivery lead, 2026-10-04). Implemented by the scheduling engineer.
- Code: `android/app/src/main/kotlin/app/daycue/integrations/calendar/`.
- Contract: PRODUCT §9 (CAL-1..6), DOMAIN.md (`CalendarEvent`, `Event.CalendarSynced`, `CalendarRules.decide`).

## Context

DayCue cues before selected Google Calendar events (PRODUCT §9) and uses in-progress meetings for
Activity = Meeting (CAL-4). It must work offline (core principle), never write to the owner's
calendar, and treat titles as untrusted data (CAL-5). Two ways to read the owner's Google calendars
exist on Android:

1. **Android Calendar Provider** (`CalendarContract`): the on-device database that the Google account
   sync adapter (and any other account's sync adapter) keeps in sync with the server.
2. **Google Calendar REST API** (`calendar/v3`) with OAuth from the app.

## Decision

Use the **Calendar Provider with `READ_CALENDAR` only**. No Google Cloud project, no OAuth client, no
tokens, no network code. `WRITE_CALENDAR` is not declared; the code only issues `query()`.

## Comparison

| | Calendar Provider (chosen) | Google Calendar API |
|---|---|---|
| Access | `READ_CALENDAR` runtime permission, one dialog | Google Cloud project, OAuth consent screen, OAuth client per signing key, `calendar.readonly` scope; tokens to store and refresh |
| Write risk | None: read-only permission, read-only code | Scope can be read-only too, but the token is a credential the app must protect |
| Offline | Yes: reads the already-synced device database | No: every refresh needs network |
| Data available | `Instances` (recurrences expanded by the provider, exceptions as separate rows with `ORIGINAL_ID` / `ORIGINAL_INSTANCE_TIME`), `Events.STATUS`, `SELF_ATTENDEE_STATUS`, `AVAILABILITY`, `HAS_ALARM`, `ORGANIZER`, `UID_2445`, `DISPLAY_COLOR`, `Attendees` (email, type, status), `Reminders` (method, minutes) | Everything, incl. `conferenceData`/`hangoutLink`, `eventType`, extended properties |
| Calendars covered | Every calendar the owner syncs to the phone, from every account (Google, Exchange, local) | Only Google calendars of the signed-in account(s) |
| Incremental sync | None: no server change feed; DayCue re-reads a bounded window and diffs itself | `syncToken` from `events.list`; must handle `410 Gone` by wiping the local store and doing a full sync again |
| Change notification | `ContentObserver` / JobScheduler content-URI trigger on `content://com.android.calendar` | Push channels (`events.watch`) need a public HTTPS endpoint, or polling |
| Freshness | Bounded by the device's own calendar sync (push-based for Google accounts on most devices, but Doze/battery saver/disabled sync delay it) | Bounded by our polling interval and network |

### Trade-offs accepted

- **Depends on device calendar sync.** If the owner disabled "Sync calendar" for the account, or the
  device is offline / in deep Doze, DayCue sees stale data. Mitigation: the engine's `maxCacheAge`
  (24 h default) stops calendar cues and Activity = Meeting from old caches, and readiness shows the last
  sync time. Sync latency of the Google sync adapter is outside our control (unverified on the owner's
  phone; see FEASIBILITY.md §6 test steps).
- **No server-side sync tokens.** DayCue reads the whole window each time (`now .. now + syncHorizonDays`,
  default 7 days; instances overlapping it) and diffs against the previous snapshot held by the engine
  (`CalendarSynced` is a full snapshot; the engine reconciles: moved -> reschedule, canceled / declined /
  removed -> retract, CAL-2). Cost: one indexed provider query + attendee/reminder queries per sync,
  milliseconds for a personal calendar.
- **Invisible calendars.** Events from calendars that are not synced to the device (sync switched off,
  account not on the phone, "Sync this calendar" unchecked in the Google Calendar app) do not exist for
  DayCue. The selection screen lists exactly what the provider has.
- **No conferencing metadata.** The provider has no `conferenceData`; the "conferencing link" condition
  is a host-name match (`meet.google.com`, `zoom.us`, `teams.microsoft.com`, `webex.com`, ...) on the
  description and location, which are scanned in memory and never stored.

## Consequences (implemented)

- Identity: instance key `<seriesEventId>@<originalInstanceTime>` for exceptions, `<eventId>@<begin>` for
  recurring instances, `<eventId>` for single events, so a rescheduled instance keeps its key (CAL-2).
- Duplicates (same iCal UID + start in two calendars, fallback title + start + end) collapse to one copy:
  accepted > tentative > needs-action > declined > canceled, then self-organizer, then lowest calendar id.
- Refresh: WorkManager periodic (30 min), WorkManager content-URI trigger (5-60 s after a provider
  change, re-armed after each run), a `ContentObserver` while the process lives, app open, and config
  changes of the selection. Worst case for an event added shortly before it starts: see FEASIBILITY.md §6.
- Room `calendar_event_cache` holds the latest snapshot (on-device only, titles included, never exported).
- Without the permission or with no calendar selected, DayCue dispatches an empty snapshot (no calendar
  cues from stale data); everything else is unaffected (CAL-6).

## Sources (read 2026-10-04)

- Calendar Provider overview: https://developer.android.com/identity/providers/calendar-provider
- `CalendarContract` reference: https://developer.android.com/reference/android/provider/CalendarContract
  (`Instances`: https://developer.android.com/reference/android/provider/CalendarContract.Instances)
- Calendar API synchronization guide (sync tokens, `410 Gone` -> wipe and full sync):
  https://developers.google.com/workspace/calendar/api/guides/sync
- Calendar API push notifications (`events.watch`): https://developers.google.com/workspace/calendar/api/guides/push
- Calendar API OAuth scopes: https://developers.google.com/workspace/calendar/api/auth
- WorkManager content-URI triggers (`Constraints.Builder.addContentUriTrigger`):
  https://developer.android.com/reference/androidx/work/Constraints.Builder

The Provider overview and the sync guide were read in this pass; the reference pages, the push and auth
guides are cited from knowledge and were not re-read.
