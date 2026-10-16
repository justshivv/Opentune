# Third-party notices

OpenTune is licensed under the GNU General Public License v3.0 (see
[LICENSE](LICENSE)). It includes or uses the following code, libraries,
fonts and data, under their own licences.

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

The Amazon catalogue and Zarz v2 session adapter follow the protocol in
[SpotiFLAC Extension](https://github.com/spotiflacapp/SpotiFLAC-Extension)
(Apache-2.0; licence in `third_party/spotiflac-extension/LICENSE`) and the
[SpotiFLAC Mobile](https://github.com/spotiflacapp/SpotiFLAC-Mobile) signed
session implementation. The Kotlin implementation, strict callback checks,
recording validation and OpenTune integration are maintained here.

Amazon container preparation uses `com.antonkarpenko:ffmpeg-kit-audio:2.2.3`,
the audio-only build of the maintained
[FFmpegKit fork](https://github.com/sk3llo/ffmpeg_kit_flutter), based on
[FFmpegKit](https://github.com/arthenica/ffmpeg-kit) and
[FFmpeg](https://ffmpeg.org). The audio package is distributed under LGPL-3.0;
see the upstream source, build instructions and component licences. OpenTune
does not modify these native libraries.
