# Third-party notices

OpenTune is licensed under the GNU General Public License v3.0 (see
[LICENSE](LICENSE)). It includes or uses the following code, libraries,
fonts and data, under their own licences.

The community lossless provider protocols, endpoint registry, SpotiFLAC session
signing and verification flow under `data/lossless/`, `ui/settings/SpotiflacVerification.kt`
and `assets/spotiflac_verify.html` are adapted from
[Spotui](https://github.com/Spotui/Spotui/tree/main/app/src/main/java/com/music/spotui/lossless)
under GPLv3. OpenTune adds YouTube Music identity lookup through
[Odesli](https://odesli.co), cancellable network requests, FLAC validation,
separate rendition caches and playback fallback.

The Qobuz relay protocol in `data/lossless/QobuzCatalog.kt` follows
[Meld's QobuzAudioProvider](https://github.com/FrancescoGrazioso/Meld/blob/main/app/src/main/kotlin/com/metrolist/music/qobuz/QobuzAudioProvider.kt)
(Metrolist Project, copyright 2026, GPLv3). The JioSaavn URL decoding and
conditional 320 kbps rendition selection follow
[BitChord's JioSaavnService](https://github.com/kushagrasinghx/BitChord/blob/main/app/src/main/java/com/music/bitchord/data/jiosaavn/JioSaavnService.kt)
(GPLv3). OpenTune adds conservative catalog matching and byte/size verification.

The Innertube client, response parser and YouTube stream resolver under
`data/innertube/` are adapted from
[BitChord](https://github.com/kushagrasinghx/BitChord), used under its
GPLv3 license. The InnerTubeX glue and the PoToken WebView
(`data/innertube/InnerTubeXResolver.kt`, `data/innertube/potoken/`,
`assets/po_token.html`) also come from BitChord; the PoToken code follows
NewPipe's design. [InnerTubeX](https://github.com/MetrolistGroup/innertubex)
by MetrolistGroup and [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor)
are used as libraries under GPLv3. Liquid Glass uses Kyant's
[backdrop](https://github.com/Kyant0/AndroidLiquidGlass) library, and
frosted glass uses [Haze](https://github.com/chrisbanes/haze). The
[Outfit](https://github.com/Outfitio/Outfit-Fonts) font is used under the
SIL Open Font License 1.1 (`third_party/outfit/OFL.txt`). Album covers
come from the [Cover Art Archive](https://coverartarchive.org) through
[MusicBrainz](https://musicbrainz.org), and recommendations from
[ListenBrainz](https://listenbrainz.org), all run by the MetaBrainz
Foundation. Headphone corrections come from
[AutoEq](https://github.com/jaakkopasanen/AutoEq) by Jaakko Pasanen (MIT).
Skip segments come from [SponsorBlock](https://sponsor.ajay.app) (data
under CC BY-NC-SA 4.0), and radio stations from the
[Radio Browser](https://www.radio-browser.info) community directory.
