# MusicLab Turbo R71 — Spotify authentication recovery: evidence and implementation gate

Status: **LAB EVALUATION; NO APP LOGIN FIX, NO APK, NO ZIP**.
Operation: **musiclab-r71-spotify-auth-recovery-20261120**.
Base: MusicLab Turbo R70 `ml70`, commit `b6e9f86e64aed114e3706514143559c183e58a2b`.
MotorLab: local verified r40. Preserve original Android applicationId, version family, signing, Scudo360, playback, Cover, Compilation, Hit Parade, YouTube mapping and all data.
No GitHub Actions, no paid resources, and no modifications to StudyBook or production branches.

## What is directly verified

- Device (2026-11-20): both Spotify email/password and Continue with Google paths fail inside MusicLab R70; the Google page says that this browser or app may be insecure.
- User: Spotify's own app signs in; older MusicLab versions also fail. The exact start date of the regression is unknown.
- MusicLab code: `SpotifyLoginScreen.kt` creates an embedded `WebView`, loads `SpotifyAuth.LOGIN_URL`, and extracts `sp_dc` from WebView cookies.
- MusicLab code: `SpotifyAuth.kt` uses `open.spotify.com/api/token` and a third-party GitHub Gist for internal TOTP token acquisition. These are not the officially supported Web API OAuth authorization flow.
- MusicLab README derives from Meld, originally designed to work without a Spotify Developer Client ID, so no app registration was required for that *unofficial* integration.
- The Spotify login module in current Meld upstream still uses embedded WebView and the same token-extraction mechanisms; an upstream CSS layout repair is not sufficient to remedy Google sign-in.
- Meld issue #342, opened November 16, 2026, reports email/Google/phone login failures in Meld 0.9.2; other users confirm the error remains after reinstalls. This supports an upstream problem but does not prove the exact provider-side change.
- Google officially rejects OAuth in embedded WebViews: `403 disallowed_useragent`.
- Official Spotify Authorization Code with PKCE requires a separately registered application `client_id` and an exact allowlisted redirect URI. No Spotify OAuth Client ID is configured in the examined MusicLab authentication code.
- Spotify February/March 2026 policy: Development Mode app ownership requires Spotify Premium and imposes a five-user limit and one newly created Client ID per developer. A normal Spotify login, by itself, does not create a developer application.

## Device proof: Spotify Embed playlist visibility (2026-11-20)

- User-supplied Android Chrome screenshot shows `https://open.spotify.com/embed/playlist/2MUEaiDQ8I6dgJlcBhLCVc` rendering a playlist titled **Rap italiano**, by **Bruno Ray Verlezza**. Eight visible numbered rows include "Littlefunkyintro" (Neffa), "L'Incognita" (Neffa), and "Tranqi Funky" (Articolo 31). There may be additional items below the screenshot; **do not infer the complete item count**.
- Proven: this playlist can be displayed in a Spotify-controlled embed in the user's phone/browser. **NOT proven**: that the page is accessible anonymously (user might already have Spotify browser session), that all track metadata is exposed in a supported machine-readable API, that other followed/private playlists can be enumerated or that remote updates can be synchronized into MusicLab's local playlist DB.
- Spotify oEmbed is designed to return **basic link metadata** (title, thumbnail, embed markup), not playlist item enumeration: https://developer.spotify.com/documentation/embeds/tutorials/using-the-oembed-api.
- Spotify iFrame API is intended for controlling embedded Spotify content, not as a playlist-item synchronization API: https://developer.spotify.com/documentation/embeds/references/iframe-api.
- Current Spotify Get Playlist Items API requires OAuth authorization and is scoped to playlists owned by/collaborated on by the authenticated user: https://developer.spotify.com/documentation/web-api/reference/get-playlists-items.
- A Spotify-controlled Embed may reflect changes on refresh; it does **not** give MusicLab a compliant, authorized feed of all followed/private playlists with song IDs and diffs. Keep this distinction explicit.
- No-cost external browser automation was not available during this check; do not spend funds to obtain this result. No changes made to user playlist or account.

## Binding user requirement: live Spotify playlist synchronization

- On 2026-11-20 the user explicitly rejected a one-off ZIP/JSON export as the MusicLab Spotify replacement. The intended behavior is **live, authenticated synchronization of the user's existing Spotify playlists into MusicLab as before**.
- A public Spotify link, web embed, manually copied list, exported JSON or local snapshot is NOT an acceptable claim of solving this requirement. Do not rename any of these as "Spotify synchronized".
- Preserve playlist IDs, track identity, ordering, refresh after remote edits, account-private playlist access where authorized, restart persistence and established Spotify-to-YouTube music matching, subject to legally available endpoints. Playback is separate from synchronization.
- For this Free account with no developer registration, find an authorized provider-supported route through an independently controlled, eligible app registration, with express authorization and permissions, **or** report a verified blocking prerequisite. Do not request a Premium upgrade or reuse an unrelated app's credentials.
- Current technical status: **LIVE SYNC NOT RESTORED**. No authenticated endpoint/client registration has yet been shown to satisfy this requirement. Defer APK creation rather than advertise an incomplete solution.
- Official Spotify sources checked 2026-11-20: https://developer.spotify.com/documentation/web-api/tutorials/february-2026-migration-guide ; https://developer.spotify.com/documentation/web-api/tutorials/code-pkce-flow ; https://developer.spotify.com/documentation/web-api/concepts/scopes

## User account and no-cost constraint — confirmed 2026-11-20

- The MusicLab user has a **Spotify Free** personal account and has **never registered** a Spotify Developer application.
- Per Spotify's February 2026 official Development Mode rules, the **app owner**, not necessarily each end user, must have active Spotify Premium. Therefore the user cannot create an operational self-owned Development Mode Spotify API integration using only this Free account.
- Spotify Free is **not** inherently disqualified from signing into an eligible third-party app whose registered owner meets the rules; however, MusicLab currently has no verified external owner, client ID, consent/redirect configuration or authorization for such a Spotify application.
- **No extra spending / no Premium purchase**. Do not request account upgrade as a prerequisite. Do not borrow unrelated third-party client IDs or secrets, scrape tokens or bypass embedded-browser security limits.
- R71 remains **BLOCKED ON SUPPORTED AUTH CAPABILITY**. Preserve signed MusicLab R70, the current user's local Spotify data and all non-Spotify features. No APK should be presented as repaired until a legitimate account sign-in and permitted API reads are confirmed end to end.
- Official current references: https://developer.spotify.com/documentation/web-api/tutorials/february-2026-migration-guide and https://developer.spotify.com/documentation/web-api/concepts/quota-modes

## Engineering decision / hard gate

Do **not** present Chrome Custom Tabs alone as a functioning fix: system browser cookies are intentionally unavailable to MusicLab; redirecting the legacy web-player login to Chrome cannot provide `sp_dc` to the existing code. Never solicit passwords, access tokens, `sp_dc`, `sp_key`, client secrets or browser cookies in chat or commit them to GitHub. Do not spoof Google user-agent, disable TLS, use injected JavaScript to extract external-browser cookies or circumvent Google's restrictions.

**Official OAuth route (conditional)**:
1. Have an eligible registered Spotify Developer app and approved `client_id`, redirect URI, scopes, and applicable policy requirements. This is an external prerequisite, not confirmed by current MusicLab source/user account.
2. Use secure external browser / Custom Tabs + PKCE S256; random state and verifier; validate callback and state; code exchange through `accounts.spotify.com/api/token`; expiry and refresh handling.
3. Use secure Android storage and never leak auth codes/tokens in logs, URLs, telemetry or screenshots.
4. Migrate Spotify-dependent data calls to officially available Web API endpoints. Existing private GraphQL/web-player tokens are NOT guaranteed to work with a new OAuth access token; do not silently reuse them.
5. Validate current 2026 Dev Mode endpoint availability and functionality (profile, playlists, liked songs, artists, search) before enabling the corresponding UI. Preserve existing player/YouTube routing and deterministic matching.

**No Developer app available**: no confirmed reliable supported Spotify account-linking mechanism. Keep R70 as existing installed app and isolate any experimental source changes in R71. Do not distribute a nominally fixed APK that just opens the browser and strands the user.

## Acceptance gates before any APK

- Real sign-in: email and Google in an external system browser, callback to the SAME signed Android package, including cancellation and expired credentials.
- Real personalized data: user's authorized profile, playlists and liked tracks; accurate permissions/scope error states.
- Verify user account remains signed in after app restart and token expiry; no cookie scraping.
- Scudo360, exact-source JUnit evidence, signer cert, package name and version family, independent ZIP integrity, zero paid services.
- Device verification remains required; passing an emulator/static test is not proof of provider account integration.

## Links / provider evidence

- https://support.google.com/accounts/answer/16668185?hl=it
- https://developer.spotify.com/documentation/web-api/tutorials/code-pkce-flow
- https://developer.spotify.com/documentation/web-api/tutorials/february-2026-migration-guide
- https://developer.spotify.com/documentation/web-api/concepts/redirect_uri
- https://github.com/FrancescoGrazioso/Meld/issues/342
- https://github.com/FrancescoGrazioso/Meld/issues/343
- https://github.com/FrancescoGrazioso/Meld

Next step: await verification of a usable registered OAuth client or an independently validated compliant provider-supported alternative; then implement and test the isolated R71 authentication module.
