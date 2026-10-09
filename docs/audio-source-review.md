# Audio source review

Reviewed 2026-10-09 against the repositories' then-current default branches. This is a source-capability review, not a listening test or a claim that every backend works in every country. READMEs and repository trees were checked for all 30 clients; the relevant provider implementations were examined for the strongest alternative-source candidates.

## Result for OpenTune

The best additional format found was genuine lossless FLAC, including Qobuz hi-res. OpenTune now tries matching Qobuz catalog results and the Spotui/SpotiFLAC Tidal → Amazon → Qobuz routes when **Lossless streaming** is enabled. A returned URL is accepted only after reading its FLAC STREAMINFO and checking precision and duration. A FLAC container cannot prove that the upstream master was never transcoded, and greater sample rates do not guarantee an audible improvement.

An independent **JioSaavn quality upgrade** option adds 320 kbps lossy audio. It requires an explicit catalog quality flag, a known CDN URL pattern, an MP4 header, and a plausible file-size-derived bitrate. It respects the selected streaming ceiling and the existing codec-aware quality score; it does not replace a pinned YouTube Premium upgrade. Bitrate is a selection heuristic, not proof of perceptual superiority across codecs or masters.

With Max selected, source selection happens before playback and before the audio cache opens. FLAC candidates are compared by verified bit depth and sample rate within a 12-second search budget; the best verified result survives timeouts from other providers. If none matches, JioSaavn is tried (up to 10 seconds), followed by YouTube. This is the best candidate found within the supported providers and deadline, not a guarantee about every recording on the internet. Other quality levels retain YouTube-first playback and optional background lossless upgrades. Track identity matching checks normalized title, artist, album when known, and duration within three seconds. Version names are retained and combining marks in non-Latin names are preserved. Conservative matching intentionally misses some valid alternatives. Every external rendition gets a distinct cache key; expiry or playback failure returns to YouTube without mixing bytes. YouTube loudness measurements are not applied to an external master. Downloads retain their existing behavior.

Higher-quality sources settings contain a shared unmetered-only default, background lossless/hi-res preferences, a compatible Qobuz relay URL, and optional SpotiFLAC verification. Max automatically enables the initial hi-res/JioSaavn search on allowed connections. Signal Path shows the active provider and the selected audio's measured or verified properties; unknown bitrate is explicitly reported rather than taken from the quality setting or a different track.

## Availability checks

- Mobile-data follow-up: **Unmetered networks only** blocks all external searches even at Max; switch it off to use these sources on mobile data. Signal Path now explains this skip and reports catalog misses, failed requests, and rejected audio probes. Shelf cards without runtime fetch metadata for the same video before matching; that request does not select or play YouTube audio.
- "Uyi Amma" from *Azaad* returned JioSaavn ID `GHXYZHMU`, duration 253 seconds, with a 320 kbps flag. Its 64-byte CDN probe returned HTTP 206, `audio/mp4`, an MP4 header and total size 10,506,899 bytes (about 332 kbps including container overhead). Artist searches returned compilations ahead of the original album, so lookups now search the requested album first. Matching recognizes recording credits including composer, singer and lyricist, excludes actors, and requires every supplied artist in a joined credit. The title, album and duration checks remain. This is a live endpoint/header check, not an on-device playback test.
- JioSaavn search and track details returned a 320-capable result for "Husn" by Anuv Jain. A 64-byte CDN range request returned HTTP 206 and an MP4 header. Reported total size 9,142,812 bytes over 217 seconds is approximately 337 kbps including container overhead. This validates that sample's route, not every catalog entry or on-device playback.
- Odesli's unauthenticated mapping endpoint returned HTTP 401 with `PUBLIC_API_ACCESS_DEPRECATED`. The Spotui-style mapping chain therefore cannot currently be relied upon without an upstream change. The Qobuz catalog route works independently of Odesli, and failed mappings have a cooldown.
- The checked public Qobuz routes were unavailable: Kennyy timed out, TrypT returned HTTP 503, and Squid did not resolve from the test environment. Jumo's advertised search path returned a frontend HTML document. Only the matching Kennyy/TrypT protocol is included as defaults; a user-operated compatible HTTPS relay can be configured. Live FLAC playback remains unverified.
- SpotiFLAC verification requires the user's interactive challenge and a non-expired session. No credentials from another client are supplied. Session preferences are excluded from Android backup.
- Verification follow-up: the provider's live `/v2/challenge` page includes `action=session` and per-challenge `cData`, which the copied inline widget omitted. OpenTune now loads that provider-hosted page for the bootstrap challenge ID and reads its top-level `zarzGrant` result, then exchanges it once for a validated session. No CAPTCHA is solved automatically. Bootstrap and page loading were checked live; interactive session issuance still requires device confirmation.

## Client inventory

“No additional source” below means no demonstrated better source identified in this review; it is not an exhaustive claim about all branches, plugins or future releases.

| Client | Source/quality evidence | OpenTune decision |
| --- | --- | --- |
| [ArchiveTune](https://github.com/rukamori/ArchiveTune) | YouTube Music client; original link redirects to rukamori. | No additional source selected. |
| [AuraMusic](https://github.com/TeamAuraMusic/AuraMusic) | YouTube Music; other named providers in the README supply lyrics. | No additional source selected. |
| [BitChord](https://github.com/kushagrasinghx/BitChord) | User-configured FLAC/ALAC modules and an optional JioSaavn provider alongside YouTube. | Adapted its GPL JioSaavn protocol with stricter identity and byte checks. Arbitrary modules are not bundled. |
| [BloomeeTunes](https://github.com/HemantKArya/BloomeeTunes) | Local playback and Rust-backed `.bex` streaming plugins. | Quality depends on the installed plugin; no universal higher-quality endpoint identified. |
| [Echo Music](https://github.com/EchoMusicApp/Echo-Music) | YouTube Music streaming. | No additional source selected. |
| [Gyawun Music](https://github.com/sheikhhaziq/gyawun_music) | YouTube Music streaming. | No additional source selected. |
| [Just-Listen](https://github.com/RLD-JL/Just-Listen) | Audius public API; README describes 320 kbps streams and source points to `api.audius.co`. | A distinct artist-uploaded catalog, not demonstrated as a better replacement for OpenTune's recordings or added 320 kbps route. No automatic substitution. |
| [Kreate](https://github.com/knighthat/Kreate) | YouTube-based client. | No additional source selected. |
| [lyraMusic](https://github.com/lyraMusicApp/lyraMusic) | YouTube Music engine; Apple Music/Spotify/etc. in its multi-provider description concern lyrics. | No additional source selected. |
| [M3-Play](https://github.com/JAY01-CYBER/M3-Play) | Inspected playback service uses YouTube. | No additional source selected. |
| [Meld](https://github.com/FrancescoGrazioso/Meld) | Qobuz FLAC/hi-res provider with community catalog and download APIs. | Added compatible catalog lookup and relay support, adapted under GPLv3. |
| [Metrolist](https://github.com/MetrolistGroup/Metrolist) | YouTube Music/InnerTubeX; original link redirects. | Existing OpenTune YouTube extraction/quality upgrades remain. |
| [Music-You](https://github.com/DanielSevillano/music-you) | YouTube Music streaming. | No additional source selected. |
| [Musify](https://github.com/gokadzev/Musify) | Current README describes plugins and external sources. | No independently verified higher-quality endpoint to bundle. |
| [Muzo](https://github.com/Shashwat-CODING/Muzo) | Frozen public snapshot: YouTube extraction, JioSaavn metadata. Current development is described as closed source. | No current playback-quality claim inferred from unavailable code. |
| [Muzza](https://github.com/Maloy-Android/Muzza) | YouTube Music client. | No additional source selected. |
| [Namida](https://github.com/namidaco/namida) | Local music/video and YouTube streaming. | Local format support does not establish a new lossless streaming source. |
| [NomaTune](https://github.com/Shahdullah/NomaTune) | Its hi-res resolver explores Bandcamp/SoundCloud through NewPipe, including lossy fallbacks/estimated bitrates. | A resolver name is not proof of lossless audio; no general replacement imported. |
| [N-Zik](https://github.com/N-Zik-Group/N-Zik) | YouTube Premium formats with user's account, plus Invidious access to YouTube. | OpenTune already has authenticated YouTube quality upgrades. |
| [RiPlay](https://github.com/fast4x/RiPlay) | Current README describes the YouTube iframe player for online content. | No additional source selected. |
| [SimpMusic](https://github.com/maxrave-dev/SimpMusic) | YouTube Opus/AAC up to 256 kbps with Premium. | Existing authenticated quality path retained. |
| [SoundPod](https://github.com/arunnechully/SoundPod) | YouTube Music streaming. | No additional source selected. |
| [SpatialFlow](https://github.com/MythicalSHUB/SpatialFlow) | YouTube Music plus local high-fidelity files. | DSP/local format support does not add a better remote master. |
| [Spotube](https://github.com/team-spotube/spotube) | Current platform is plugin-driven; original link redirects. | Quality depends on plugin/source, not Spotify metadata alone. |
| [Spotui](https://github.com/Spotui/Spotui) | SpotiFLAC registry, Tidal/Amazon/Qobuz resolving and gated-session protocol. | Adapted requested lossless subsystem; encrypted/segmented responses are rejected by this integration. |
| [Stash](https://github.com/rawnaldclark/Stash) | Qobuz through a user's subscription, own relay, or a signed-config relay in official builds. | Added a compatible custom-relay option; no reuse of Stash's release credentials or signed configuration. |
| [Tryptify](https://github.com/tryptz/Tryptify) | Tidal up to 24-bit FLAC, Qobuz and Deezer FLAC/MP3 through configured servers. | Confirms lossless source options, but no stronger generally available endpoint was verified. Existing new Qobuz/Spotui paths cover the demonstrated format benefit. |
| [Velune](https://github.com/nikhilvishwakarma00/Velune) | YouTube Music client with audio processing features. | No additional source selected. |
| [ViTune](https://github.com/bartoostveen/ViTune) | YouTube Music; original link redirects. | No additional source selected. |
| [VIVI Music](https://github.com/vivizzz007/vivi-music) | YouTube plus a JioSaavn implementation in the tree. | JioSaavn implemented from GPL BitChord references; VIVI's restricted provider code was not reused. |

## Main implementation references

- [Spotui lossless package](https://github.com/Spotui/Spotui/tree/main/app/src/main/java/com/music/spotui/lossless)
- [Meld QobuzAudioProvider](https://github.com/FrancescoGrazioso/Meld/blob/main/app/src/main/kotlin/com/metrolist/music/qobuz/QobuzAudioProvider.kt)
- [BitChord repository](https://github.com/kushagrasinghx/BitChord)
- [Stash source and lossless documentation](https://github.com/rawnaldclark/Stash#lossless)
- [Just-Listen Audius API configuration](https://github.com/RLD-JL/Just-Listen/blob/master/shared/src/commonMain/kotlin/com/rld/justlisten/datalayer/utils/Constants.kt)

See [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md) for adaptation credits. End-to-end Android playback, interactive verification, device output precision and regional catalog availability still require device testing.
