# OpenTune on Android TV and Google TV

The Android APK also runs on Android TV and Google TV. Install the signed APK from
OpenTune's GitHub release or preview artifact, then open OpenTune from the TV's apps
row. Google Play availability additionally requires a TV store submission and review;
the launcher declaration does not publish the app to Google Play.

TV detection selects landscape, overscan-safe margins, a labeled navigation rail,
and a two-column full-screen player. It overrides saved phone dock/side-player
preferences. Layout sizes use density-independent units for HD, Full HD and 4K TVs.

## Using the remote

- Use the arrows to move the visible focus ring. OK activates the focused item.
- Search opens a text field supported by the TV's installed on-screen keyboard.
- Search, Home, Library, Settings and Player remain reachable from the navigation rail.
- In the player, focus starts on Play/Pause. Focus the seek bar and press Left/Right
  to seek; focus the volume bar to change the active output's volume.
- Lyrics and Queue have explicit buttons, including when lyrics preview is disabled.
- Queue entries have Remove and, with shuffle off, Move up/Move down buttons.
- Back leaves lyrics/queue first, then closes the player, then returns through pages.
  The player uses a separate focus window so navigation cannot land on hidden pages.
- Play/Pause, Next, Previous, Fast-forward and Rewind media keys work in the foreground.
  The existing media session continues handling media keys and playback in the background.
- Sign in from Settings using the existing phone/QR pairing flow where available.
  Browsing and playlist actions use the same account and playback service as Android.

## Verification

Automated checks cover TV detection, navigation with arrows and OK, player focus,
remote seeking, explicit lyrics/queue access, and layout selection at different
window sizes. Before publishing a TV store release, also test on physical Android TV
and Google TV devices: cold launch/onboarding, on-screen keyboard, sign-in, dialog
focus and Back, playlist actions, media keys with the app backgrounded, and resume
with music playing. Device firmware, IME and HDMI volume behavior vary by TV.
