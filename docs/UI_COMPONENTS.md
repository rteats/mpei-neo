# MPEI Neo · UI component map

| What you see | Compose implementation | Behavior |
| --- | --- | --- |
| Short bottom navigation | ShortNavigationBar / MpeiNeoApp.kt | Four short icon-and-label items, horizontal icon positioning, equal weights. |
| Schedule destination (Пары) | MpeiNeoApp.kt / ScheduleAgenda.kt | Tap to switch to Schedule and jump to today even if already selected. |
| BARS navigation item | MpeiNeoApp.kt | Becomes a back arrow labelled Оценки when the BARS browser is visible. |
| Top search bar | ScheduleDockedSearch.kt | Collapsed placeholder displays the selected group, teacher, or room (including a custom favorite name). No separate target card or month/year header. |
| Expanded search | ScheduleDockedSearch.kt | Favorites appear immediately on focus. As text is entered, matching favorites stay above API results; category filters apply to both. |
| Favorite row overflow | ScheduleDockedSearch.kt | Rename or remove favorite. Blank name restores the original display name. |
| Custom-name persistence | UserPreferences.kt | Names stored separately by schedule-target type and id; existing favorites are preserved. |
| Date-grouped timeline | ScheduleAgenda.kt | Native sticky day headers in a bounded, scrolling LazyColumn. Pull to refresh; weeks loaded on demand. |
| Class card | LessonCard / ScheduleScreen.kt | Number pill, time, kind, subject, and icons for room, lecturer and groups. |
| Settings | SettingsScreen.kt | Update, diagnostics and cache; refresh-on-launch toggle removed. |

## Data behavior

Favorites are stored in the existing DataStore favorites list. Optional custom names
live in a separate map keyed by ScheduleTarget.favoriteKey() (type plus id).
The stored target retains its official name, which remains searchable.
Removing a favorite also removes its alias. Local favorites match even one-character
queries, while remote search is debounced by the ViewModel.

At every app launch the selected current week is loaded from cache if available
and refreshed from the network; the user can also pull down in the agenda for
an explicit refresh. The former month/year, Today button, stale badge, refresh
button, and target selector card are removed.

Reference: Google Material 3 Expressive ShortNavigationBarWithHorizontalItemsSample,
adapted with equal-weight items to keep four destinations usable on narrow phones.
