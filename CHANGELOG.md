# Changelog

## [v1.10.1] - 2026-11-24

### Fixed — BetterLyrics
- Fixed Cloudflare HTTP 403 rejections on `lyrics-api.boidu.dev` by sending browser-compatible `User-Agent`, `Origin`, and `Referer` headers via `lyricsGetResponse`.
- Added 5-tier descending specificity query fallback (`s+a+d+al` -> `s+a+d` -> `s+a` -> comma-normalized collaboration artists -> `coreTitle+primaryArtist`) in `BetterLyrics.kt` and `web/app/src/lyrics/providers.ts` to eliminate HTTP 401 cache misses caused by album mismatches, music-video durations, or `&`/`feat.` artist formatting.
- Added automatic failover between `api.betterlyrics.org` and `lyrics-api.boidu.dev` on transport failures, HTTP 403, HTTP 429 rate limits, and 5xx errors.
- Added optional BetterLyrics `X-API-Key` support in `AppSettings` and `LyricsSourcesDialog` (with `public normalizeBetterLyricsApiKey` shared across `:app` and `:tv`).
- Enabled tap-to-retry for missed (`NOT_FOUND`) providers in `LyricsProviderSheet` and `MainViewModel.selectLyricsProvider`.
- Added `MockWebServer` integration and unit tests in `BetterLyricsTest.kt` and `web/app/src/lyrics/providers.test.ts`.

### Improved — Lyrics & Player UI
- Upgraded `LyricsStatusWithChange` to a compact frosted-glass pill chip with hairline border, press-scale animation, and chevron disclosure icon (without stretching across full-width containers).
- Redesigned `LyricsProviderSheet` with frosted cards, active/found hairline borders, `Word-synced` quality badges, provider descriptions, press-scale animation, and retry state indicators.
- Polished `LyricsSourcesDialog` with a subtle outer glass border, dragging row highlight, inline `Word-synced` badges, and unified API key rows (`Set` / `Optional` status pills) for BetterLyrics and PaxSenix.
- Refined `LyricsSkeleton` shimmer easing/geometry and added a breathing alpha pulse to `LyricsLoadingLine`.
- Added localization strings across all supported locales (`app/src/main/res` and `sharedUi/composeResources`).

### Release Metadata & Update Continuity
- Bumped phone, Wear OS, and Android TV versions to `1.10.1` (`versionCode = 43`).
- Backfilled missing v1.10 localization keys across all 17 non-English Android locales so `LocaleStringsTest` passes across every language.
- Pointed `AppUpdateChecker` to `BuildConfig.UPDATE_REPO` (`Baran3575/BitChord` by default) so in-app updates preserve fork customizations and signing continuity.
- Configured parallel production package IDs (`com.music.bitchord.plus` / `BitChord+` for phone & Wear OS, `com.music.bitchord.plus.tv` / `BitChord+ TV` for Android TV) so releases install side-by-side alongside original BitChord.
- Added `.github/workflows/upstream-sync.yml` (automated conflict-checked upstream merges from `kushagrasinghx/BitChord@main` into `Main2`), `.github/workflows/release.yml` (tagged Phone/TV/Wear APK releases), and updated `.github/workflows/android.yml` to build safely on `Main2`.
