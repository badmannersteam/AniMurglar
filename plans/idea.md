# AniMurglar — Product Idea and End-to-End Flow

## Product idea
- Desktop app (JetBrains Compose) to combine anime raws, dubbed tracks, and optional subtitles into a final merged output.
- Primary UX goal: keep the app as simple as possible (`"one button to do fine"`) while still exposing required choices (torrent, episodes, dubs, optional subtitle teams).
- Core stack (current target):
  - UI: Compose Desktop + Material 3
  - Architecture: MVI Kotlin (strict feature stores)
  - DI: Koin
  - Networking: Ktor Client + OkHttp
  - Media processing: ffmpeg
  - Logging: Log4j

## Target MVI architecture constraints
- Every UI feature has its own store.
- Every store has its own dedicated coroutine executor.
- Every store contract is built from `Intent` / `State` / `Message` (+ `Label` when one-time events must be published to other stores).
- `Intent` / `State` / `Message` / `Label` definitions must be declared inside the corresponding Store interface.
- `RootStore` / `RootScreen` is only a coordinator/host:
  - hosts Shikimori search component,
  - reacts to selected anime and dispatches search to feature stores,
  - hosts feature components and `Start` FAB.
- Long-running feature components must own their own progress UI and cancellation actions where required.

## Planned user flow
1. User enters anime title name in Shikimori search field and presses `Search` (or Enter).
2. User picks a matched Shikimori entry from dropdown suggestions; app expands all relevant names and starts Nyaa + dubs + subtitles discovery in parallel.
3. Nyaa picker component queries `nyaa.si` for every expanded title, deduplicates by details-page link, then enriches torrents and shows selectable torrent results + episodes.
   - For Nyaa querying/parsing references:
     - `https://github.com/marcpinet/nyaadownloader/raw/refs/heads/main/util/nyaa.py`
     - `https://github.com/marcpinet/nyaadownloader/raw/refs/heads/main/util/torrent_parser.py`
   - Querying must be observable/cancellable in component UI (progress bar + current request/total request indicator while details pages are being fetched).
   - Parse torrent names for useful metadata (name, resolution, size, codecs, season, episode info, etc.).
   - Support both torrent types:
     - Full season torrent (single unit).
     - Single-episode torrents (must be aggregated by name into one logical group).
   - Torrent picker UX: show one selected-candidate card in main view; clicking it opens dialog with all parsed entries and metadata.
   - Episode selection UX: use compact chip-based multi-select.
4. Dubs picker component queries dubs source(s) for anime-title candidates across all expanded titles, shows one selected candidate card + dialog list, then loads available dubs for selected candidate and allows multi-select.
   - Must support multiple dub providers via an abstraction layer.
   - Dubs source contract should map anime name -> dubs list.
   - Each dub includes: dub team name, views count, and episode list.
   - Episode item includes: episode number, content link, and format (for now: HLS with video+audio in one file).
   - First implementation target: `animelib.org` -> Kodik flow.
     - Discover episodes/players.
     - Parse team + views + Kodik source from players entries.
     - Resolve Kodik source to final HLS manifest.
5. Subtitle picker component resolves Russian subtitles from Anime365 by selected Shikimori link and allows optional multi-select of subtitle teams.
   - No subtitle source abstraction is planned while Anime365 is the only implementation.
   - Subtitle discovery is 1-to-1 from Shikimori URL, so the UI has only a subtitle team picker card without title-candidate selection.
   - Component is placed under the dub picker and follows the same selection-card/dialog interaction style.
   - Default selection is empty; missing Anime365 subtitle entries for selected episodes are skipped during download.
6. User chooses 1 torrent/torrent-group, N episodes, N dub tracks, and optionally subtitle teams; `Start` FAB is enabled only when required Nyaa and dubs selections are present.
7. User presses `Start`; FAB hides after start and execution moves to download + processing components.
8. Downloader component downloads selected torrent(s), selected dubs, and selected subtitles in parallel.
   - Must show progress groups:
     - Progress for every file in selected torrent/group.
     - Progress for every dub file for every selected dub.
     - Progress for downloaded subtitles per subtitle team and episode.
   - Must provide cancel-all-downloads action.
9. Processing component runs audio processing + sync + merge pipeline. Processing UI must expose two progress groups - `sync` (aggregates analyze + apply, per episode dub) and `merge` (per episode) and a cancel-all-processing action.
10. App extracts original audio tracks from downloaded raws with ffmpeg into a temp directory.
11. App analyzes original audio vs dubbed audio using envelope correlation to determine segment-wise sync operations.
   - Normalize both tracks with ffmpeg to analysis-friendly WAV (mono, 16 kHz, PCM s16le).
   - Build envelope series in Kotlin (`10 ms` windows => `100 Hz` fine series via `ln(1 + meanAbs)` + `p95` normalization + smoothing), then derive `25 Hz` coarse series.
   - Find monotonic anchor pairs using normalized cross-correlation on coarse windows (first global search, then local search around expected position).
   - Convert anchors into piecewise `rate`/`offset` mapping, split on discontinuities, then locally refine segment boundaries on fine series (±3 s window).
   - Analyzing service contract idea: input = original video/audio path + list of dub audio paths; output = deterministic per-dub align segments + gap segments + confidence diagnostics.
12. App applies computed sync operations with ffmpeg (`atrim`/`atempo`/`concat` + silence fill for unmatched gaps).
   - Sync applying service contract idea: input = list of dub audio paths + computed align segments; output = list of synced dub audio paths aligned to raw episode timeline.
   - For practical debug/testing in phase 8, provide a simple CLI utility that accepts 3 paths (original audio, dub audio, output audio), runs full analyze+apply, and logs all intermediate diagnostics.
13. App prepares selected subtitles for merge.
   - Downloaded full `ASS` subtitles are embedded as subtitle tracks named by team.
   - A caption-only version is generated from every full subtitle by keeping sign/song/caption-style dialogues and is embedded before its full subtitle track as `Team name (Надписи)`.
   - Subtitle fonts downloaded from Anime365 are attached to the final `MKV`, deduplicated by font file name.
14. App merges video + original audio + dubbed tracks + subtitles into final output with ffmpeg.
   - Merge service contract idea: input = original video path + list of processed dub audio paths + downloaded subtitle tracks + subtitle font paths + target dir path; output = nothing, output file is written to disk.
   - Existing streams from the original raw must be preserved, while newly added tracks are ordered and the first track is marked as default.

## Functional constraints and principles
- Keep flow simple for user while preserving technical correctness.
- Make long operations observable and cancellable.
- Prefer robust metadata parsing and explicit data models over ad-hoc string handling.
- Keep processing pipeline resumable/recoverable where possible (later phases).
- Root layer must remain coordinator-only; feature state and side effects belong to dedicated feature stores/executors.
- When user picks another upstream anime entry, dependent feature stores must be reset; if downloader/processing is running, ask for cancellation confirmation before switching selection.
- Main screen layout must be adaptive for desktop landscape:
  - Full-width `Shikimori` block at top.
  - Remaining height split into two tabs:
    - first: `Nyaa` on the left, `Dubs` + `Subtitles` on the right,
    - second: `Downloader` on the left, `Processing` on the right.
- UI consistency rule: identical actions across components must use identical interaction patterns and visual style (typography, chip style, card behavior).
- Do not show non-error status text labels; state must be communicated through progress indicators, visible data blocks, and content changes.
- In idle/reset state, components and their empty sections should be hidden.
- Cancel actions must be positioned in the top-right area of component cards and hidden when action is not available.
- Potentially large lists must use lazy scrollable containers with visible scrollbar.
