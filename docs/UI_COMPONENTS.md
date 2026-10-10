# MPEI Neo · UI component map

| What you see | Compose implementation | Behavior |
| --- | --- | --- |
| Short bottom navigation | ShortNavigationBar / MpeiNeoApp.kt | Four icon-only Material 3 Expressive destinations; native horizontal pager uses identical full-height page constraints to avoid jumps when changing tabs. |
| Schedule destination (Пары) | MpeiNeoApp.kt / ScheduleAgenda.kt | Tap to switch to Schedule and jump to today even if already selected. |
| BARS navigation item | MpeiNeoApp.kt | Becomes a back arrow labelled Оценки when the BARS browser is visible. |
| Bottom floating search bar | ScheduleDockedSearch.kt | Raised Material surface (shadow elevation 10dp), bottom-to-top fading scrim; floats immediately above the navbar. Collapsed placeholder displays the selected group, teacher, or room (including a custom favorite name). No separate target card or month/year header. |
| Expanded search | ScheduleDockedSearch.kt | Favorites appear in a floating panel ABOVE the bottom search input on focus. As text is entered, matching favorites stay above API results; category filters apply to both. The popup overlays the agenda without resizing it. It responds to keyboard insets and is dismissed when navigating away. |
| Favorite row overflow | ScheduleDockedSearch.kt | Rename or remove favorite. Blank name restores the original display name. |
| Custom-name persistence | UserPreferences.kt | Names stored separately by schedule-target type and id; existing favorites are preserved. |
| Date-grouped timeline | ScheduleAgenda.kt | Native sticky day headers in a bounded, scrolling LazyColumn. Pull to refresh; weeks loaded on demand. |
| Class card | LessonCard / ScheduleScreen.kt | Collapsed by default: number pill, single-line ellipsized subject, classroom at right. Tap anywhere to expand full time, kind, multiline name and icon-led room, lecturer and groups, then tap again to collapse. |
| BARS grade combinations | GradeForecastDropdown / BarsGradeCalculator.kt | Every discipline's details sheet has an expandable calculator for weighted grade combinations satisfying Σ(grade × weight) ≥ 4.2. Known grades stay fixed; ungraded controls are varied 2–5. Does not invent missing weights. |
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

Navigation retains Material 3 Expressive ShortNavigationBar but removes all visual labels; each icon has an accessibility description. The search popup stays above the input and adapts to the IME.

## Native tab swipes and WebViews

All four pages are pages of one horizontal Compose pager. Native Schedule,
BARS grades, and Settings accept horizontal swipes, synchronized with icon
selection. BARS login/browser and the OWA Mail WebView retain native WebView
touch handling instead of allowing the pager to steal website interactions.
Narrow 24dp edge-gesture areas permit swiping between those WebView pages and
their neighbors. The Schedule search is part of the Schedule pager page itself,
so it travels together with the timeline and does not resize the page during
navigation. The search scrim and gradient are independent of the navbar.

## BARS weighted-grade forecasts

The calculator consumes only BARS control activities with readable grade
weights. Decimal comma, fractional weights and percentages are supported;
missing or inconsistent weights are not silently guessed. It keeps existing
grades fixed, explores future values 2–5 and lists up to 30 combinations
which meet a weighted sum of at least 4.2. The sum, known weight total, and
limitations are visible. Forecasts are local and never modify BARS grades.
