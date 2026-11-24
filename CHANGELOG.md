# Changelog

## [v1.10.1] - 2026-11-24

### Fixed — BetterLyrics
- Added 4-tier descending specificity fallback (`s+a+d+al` -> `s+a+d` -> `s+a` -> `coreTitle+primaryArtist`) in `BetterLyrics.kt` and `web/app/src/lyrics/providers.ts` to resolve HTTP 401 cache misses.
- Switched primary endpoints to `api.betterlyrics.org` with fallback to `lyrics-api.boidu.dev` and HTTP 429 rate-limit backoff.
- Added optional BetterLyrics `X-API-Key` support in `AppSettings` and `LyricsSourcesDialog`.
- Added upstream Apple TTML (`lrc.red`) fallback when keyless BetterLyrics lookups return HTTP 401 across all tiers.
- Added unit tests in `BetterLyricsTest.kt`.

### Improved — Lyrics & Player UI
- Upgraded `LyricsStatusWithChange` to a frosted-glass pill chip with hairline border, press-scale animation, and chevron icon.
- Redesigned `LyricsProviderSheet` with rounded frosted cards, active borders, Word-synced badges, and provider descriptions.
- Added Word-synced badges, Set/Optional API key pills, and BetterLyrics API key settings in `LyricsSourcesDialog`.
- Refined `LyricsSkeleton` shimmer and added a breathing alpha pulse to `LyricsLoadingLine`.
- Added Turkish and English localization strings.

### Release Metadata
- Bumped phone and Android TV versions to `1.10.1` (`versionCode = 43`).
