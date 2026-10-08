# MPEI Neo UI component map

This file names the visible UI pieces using the same terminology as the Compose code, so feedback can point to a specific component.

| What you see | Component name to use in feedback | Compose implementation | Notes |
| --- | --- | --- | --- |
| Bottom row of app destinations | **Bottom navigation** / **NavigationBar** | `NavigationBar` in `MpeiNeoApp.kt` | Icon-only. Contains Schedule, BARS, Mail and Settings. |
| Schedule tab icon | **Schedule navigation item** | first `NavigationBarItem` | Search is no longer a separate bottom-navigation destination. |
| BARS tab icon | **BARS navigation item** | `NavigationBarItem` + `ic_nav_bars` | Opens the native BARS dashboard. |
| Mail tab icon | **Mail navigation item** | `NavigationBarItem` + `ic_nav_mail` | Opens the embedded OWA WebView. |
| Settings tab icon | **Settings navigation item** | `NavigationBarItem` | Opens app settings. |
| Current group / teacher / room card | **Schedule target selector** | `ScheduleTargetSelector` | Tapping the card opens the favorites dropdown. |
| Magnifying-glass button inside the current schedule card | **Schedule search button** | `IconButton` in `ScheduleTargetSelector` | Opens `SearchScreen`. |
| Star button in the current schedule card | **Favorite toggle** | star `IconButton` in `ScheduleTargetSelector` | Adds/removes the current target from favorites. |
| Drop-down list of saved groups / teachers / rooms | **Favorites dropdown** | `DropdownMenu` inside `ScheduleTargetSelector` | Used to switch the current schedule target. |
| Week date range plus weekday boxes | **Week selector panel** | `WeekSelectorPanel` | Swiping this panel moves between weeks; the entire panel animates. |
| Text like “5 окт. — 11 окт.” | **Week range** | range `Text` inside `WeekSelectorPanel` | On a non-current week, tapping the range returns to the current week without changing panel height. |
| Individual weekday box such as “ПТ 9” | **Day selector item** | per-day `Surface` inside `WeekSelectorPanel` | Tapping changes the visible day. |
| Horizontally swipeable daily schedule | **Day pager** | `HorizontalPager` in `WeekPager` | Swiping here moves between days, not weeks. |
| One lesson block | **Lesson card** | `LessonCard` | Contains time, lesson type, title, room, lecturer and groups. |
| Small pill such as “Лекция” | **Lesson type chip** | `LessonTypeChip` | Shown in the top-right corner of a lesson card. |
| Pull-down refresh interaction over lessons | **Pull-to-refresh container** | `PullToRefreshBox` | Refreshes the current schedule. |
| Screen used to find a group / teacher / room | **Search screen** | `SearchScreen` | Opened from the schedule search button; Android Back returns to Schedule. |
| Native marks/profile page in the BARS tab | **BARS dashboard** | `BarsScreen` / `BarsNativeDashboard` | MpeiX-style profile header followed by one card per discipline. |
| Official BARS page shown while signing in | **BARS authentication WebView** | persistent `WebView` inside `BarsScreen` | Handles the official password and 2FA flow without storing the password in the app. |
| “Открыть БАРС” browser overlay | **BARS session WebView** | the same persistent `WebView` inside `BarsScreen` | Opens the already authenticated BARS session. |
| One discipline on the BARS dashboard | **BARS discipline card** | `DisciplineCard` | MpeiX-style card with subject, assessment type, teacher and compact mark chips. |
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
