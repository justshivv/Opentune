# Echo motion

Choose **Settings → Motion → Echo motion preset → Apply** to combine:

| Setting | Echo option |
| --- | --- |
| Player → Player background | Echo glow: two drifting radial lights with a 20-second colour cycle |
| Player → Player buttons | Echo: an accent-coloured nine-lobed button with an 8-second rotation |
| Motion → Player opening | Echo: a soft spring without overshoot, on opening and closing |
| Lyrics → Lyrics animation | Echo: 400 ms line focus, 300 ms opacity, subtle timed-word lift and glow |

Existing defaults stay unchanged. Each choice can be changed individually after
applying the preset. It does not overwrite reduced animation, theme colours,
playback quality, library preferences or lyric sources.

The glow and rotation hold their positions while paused and when the app is not
resumed. Reduce animation freezes them and uses minimal lyric motion; Android's
disabled animator setting also stops the decorative clocks. The glow uses the
current light/dark palette to preserve contrast. Word effects require real word
timestamps; songs with line timing use line focus alone.

These selected effects are adapted from Echo Music, rather than its entire UI.
The preset introduces no new network calls or downloaded animation assets.
Source revision and licence are listed in [third-party notices](../THIRD_PARTY_NOTICES.md).
