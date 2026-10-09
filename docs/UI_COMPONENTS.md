# MPEI Neo UI component map

This file names the visible UI pieces using the same terminology as the Compose code, so feedback can point to a specific component.

| What you see | Component name to use in feedback | Compose implementation | Notes |
| --- | --- | --- | --- |
| Bottom row of app destinations | **Floating navigation toolbar** / **HorizontalFloatingToolbar** | `HorizontalFloatingToolbar` in `MpeiNeoApp.kt` | Floating pill above the bottom edge; hides on vertical scroll; icon-only Schedule/BARS/Mail/Settings. |
| Schedule tab icon | **Schedule navigation item** | first `FloatingDestination` / `FilledIconToggleButton` | Search is no longer a separate bottom-navigation destination. |
| BARS tab icon | **BARS navigation item** | `FloatingDestination` + `ic_nav_bars` | Opens the native BARS dashboard. |
| Mail tab icon | **Mail navigation item** | `FloatingDestination` + `ic_nav_mail` | Opens the embedded OWA WebView. |
| Settings tab icon | **Settings navigation item** | `FloatingDestination` | Opens app settings. |
| Current group / teacher / room card | **Schedule target selector** | `ScheduleTargetSelector` | Tapping the card opens favorites; no explanatory "tap to select" text. |
| Search input at the top of Schedule | **Docked schedule search** | `ScheduleDockedSearch` (`AppBarWithSearch` + `ExpandedDockedSearchBarWithGap`) | Results expand over the agenda; selecting a result updates the schedule without navigation. |
| Star button in the current schedule card | **Favorite toggle with badge** | `BadgedBox` + `Badge` + `FilledIconToggleButton` | Toggles current target, displays saved favorites count above the star. |
| Drop-down list of saved groups / teachers / rooms | **Favorites dropdown** | `DropdownMenu` inside `ScheduleTargetSelector` | Used to switch the current schedule target. |
| Date-grouped scrolling schedule | **Agenda timeline** | `ScheduleAgenda` / `LazyColumn` | Virtualized continuous days and weeks; loads adjacent weeks on demand. |
| Headings such as “Пятница, 9 октября” | **Sticky agenda day header** | `AgendaDayHeader` overlay in `ScheduleAgenda` | Pinned weekday/date while scrolling inside a day; current date uses Material You primaryContainer. |
| Jump to today | **Today action** | `ScheduleAgenda` | Scrolls to the current date. |
| Consecutive weeks | **Lazy agenda weeks** | `MainViewModel.ensureAgendaWeek` | Reuses cached weeks, downloads only visible and adjacent weeks. |
| One lesson block | **Lesson card** | `LessonCard` / `ListItem` | Experimental three-line list: overline = time + type, headline = lesson title, supporting = room/teacher/groups. |
| Small pill such as “Лекция” | **Lesson type chip** | `LessonTypeChip` | Shown at the trailing end of the overline in a lesson list item. |
| Refresh current agenda week | **Agenda refresh badge** | `BadgedBox` + `Badge` on refresh `IconButton` inside `ScheduleAgenda` | Shows a dot if visible week's last fetch is older than 30 minutes; does not imply a new timetable exists. Pull-to-refresh still works. |
| Search result dropdown | **Docked search results** | `ScheduleDockedSearch` | Filter chips, target results and favorites; no separate Search route. |
| Native marks/profile page in the BARS tab | **BARS dashboard** | `BarsScreen` / `BarsNativeDashboard` | Expressive tonal profile header, semester summary counters and discipline cards. |
| Official BARS page shown while signing in | **BARS authentication WebView** | persistent `WebView` inside `BarsScreen` | Handles the official password and 2FA flow without storing the password in the app. |
| “Открыть БАРС” browser overlay | **BARS session WebView** | the same persistent `WebView` inside `BarsScreen` | Opens the already authenticated BARS session. |
| One discipline on the BARS dashboard | **BARS discipline card** | `DisciplineCard` | Extra-large rounded card with subject, assessment badge, labeled teacher, grade chips and details arrow. |
| Sheet opened by tapping a BARS discipline | **BARS discipline details** | `DisciplineDetailsSheet` | Shows the subject's control activities/test schedule, week, weight and final grades. |
| Mail embedded browser page | **Mail portal** | `WebPortalScreen` with tag `portal-mail` | Loads `https://mail.mpei.ru/owa`. |
| Settings page | **Settings screen** | `SettingsScreen` | App preferences, updater and cache controls. |
| GitHub Releases updater | **Update card** | `UpdateCard` | Checks releases, downloads the signed APK, verifies GitHub's SHA-256 digest when present and opens Android's installer. |
| Launcher icon foreground | **Launcher foreground glyph** | `ic_launcher_foreground.xml` | Shared by regular red/white adaptive icon and Material You monochrome icon. |

## Example feedback

Instead of “the top thing is too large”, use wording like:

- “Make the **Schedule target selector** 8 dp shorter.”
- “The **Week selector panel** should have less vertical padding.”
- “Make the **Lesson type chip** more contrasty.”
- “The **Day pager** swipe animation feels too slow.”
- “Increase padding around the **Launcher foreground glyph**.”

That wording maps directly to one Compose component or resource.


## Diagnostics terminology

| What you see | Component name to use in feedback | Implementation | Notes |
| --- | --- | --- | --- |
| Runtime log card in Settings | **Diagnostics card** | SettingsScreen / DiagnosticLog | Persistent rolling log that survives app restarts and updates. |
| Copy button | **Copy diagnostics** | Clipboard action in SettingsScreen | Copies the current runtime log as plain text. |
| Clear button | **Clear diagnostics** | DiagnosticLog.clear | Clears the persistent runtime log. |

## Material 3 Expressive experiment

Reference: AndroidX `ScrollableHorizontalFloatingToolbarSample`, `FilledIconToggleButtonSample`,
three-line `ListItem` (overline/headline/supporting), `PullToRefreshWithLoadingIndicatorSample`,
and `ContainedLoadingIndicatorSample`.

- **Floating navigation toolbar**: `HorizontalFloatingToolbar` with
  `FloatingToolbarDefaults.exitAlwaysScrollBehavior(Bottom)`. Navigation controls are
  filled icon toggle buttons and reflect the active destination. Search counts as Schedule.
  The toolbar overlays full-height page content; its surrounding area is transparent,
  and there is no bottom navigation surface. Scrollable lists use trailing scroll content
  padding so the last lesson, grade, or setting can still scroll above the toolbar.
- **Favorite toggle**: `FilledIconToggleButton`, including search results.
- **Lesson item**: `ListItem` presented in a tinted, rounded `Surface`; readable time and
  full room/lecturer/group information are retained.
- **Schedule/BARS refresh**: `PullToRefreshDefaults.LoadingIndicator`.
- **Loading screens**: `ContainedLoadingIndicator` for initial schedule, search,
  BARS authentication/session check, and update checking.
- **Theme**: Monet dynamic colors retained with `MotionScheme.expressive()`.

This experiment does not change schedule fetching, BARS authentication, grades caching,
or the dev/stable updater channels.

## Android API levels

- `compileSdk`: Android 37.0, required by the experimental Material 3 1.5.0-alpha29 dependency; it does **not** raise the minimum device version.
- `targetSdk`: Android 16 (36). Changes Android compatibility behavior and applies to Google Play publication requirements; independent of `compileSdk`.
- `minSdk`: Android 8 (26). Determines the oldest Android device able to install MPEI Neo.

The overlaid floating toolbar layout is implemented without changing these SDK targets.

## Agenda + docked search redesign

- **Search**: AndroidX `DockedSearchBarScaffoldSample` pattern using `AppBarWithSearch` and
  `ExpandedDockedSearchBarWithGap`. Queries are debounced by the existing ViewModel (200 ms);
  choosing a group/teacher/room updates the agenda in place. Search is not a separate tab/screen.
- **Agenda timeline**: one virtualized `LazyColumn` with day headings; dates continue vertically
  across week boundaries. Only visible and immediate-neighbor weeks are loaded, using existing
  on-disk cache. Jump to today and manual refresh are available in the agenda header.
- **Background**: Material You `colorScheme.background` is painted at the root and Schedule
  scaffold. The floating toolbar still overlays page content; surrounding area does not have
  a reserved solid navigation-bar strip. This avoids showing Android's window background
  through a transparent root scaffold.

## Expressive polish: search, sticky dates, favorites and grades

- **Search vertical spacing**: `ScheduleDockedSearch` disables its own status-bar inset
  because the outer app scaffold already accounts for it. The unused calendar
  navigation icon is removed, leaving a full-width docked search field.
- **Pinned weekdays**: `AgendaDayHeader` stays at the top of the timeline as
  the user scrolls lessons. The header reflects the day of the first visible item.
- **Favorites**: the star is a checked `FilledIconToggleButton` with a numeric
  `BadgedBox` / `Badge`; the card still opens the saved-schedules dropdown.
  Removed the instructional text and the duplicate favorites counter.
- **Schedule freshness**: a dot on the Refresh icon denotes that the currently
  visible week was fetched **over 30 minutes ago**. The dot is informational;
  it does not assert the timetable has changed. The counter for cache/network
  source is no longer presented in the target card.
- **BARS profile**: prominent `primaryContainer` surface, original profile
  details, browser action, and three factual semester summary figures.
- **BARS disciplines**: rounded tonal `DisciplineCard` with headline hierarchy,
  assessment chip, separate teacher label and flow-wrapped color-coded grades.
  Existing BARS authentication, grade cache TTL and details bottom sheet remain.
