package com.opentune.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import android.content.Context
import com.opentune.R

/** UI chrome only. Recording names and user-created text remain untouched. */
private fun resourceFor(text: String): Int? = when (text) {
        "Home" -> R.string.ui_3a78695388b3
        "Explore" -> R.string.ui_3b73900b8d29
        "Podcasts" -> R.string.ui_6ac749b31f9a
        "Library" -> R.string.ui_dc20b3d5d2cd
        "Search" -> R.string.ui_49c266baaaa7
        "Settings" -> R.string.ui_74a883a037bc
        "Language" -> R.string.ui_a4fe65264ef7
        "App language" -> R.string.ui_b8352b44a588
        "System language" -> R.string.ui_6ae0ffc6f1b7
        "Account" -> R.string.ui_7e1b0d5641f2
        "Accounts" -> R.string.ui_8a7c8b67fe8b
        "Appearance" -> R.string.ui_3907fa7f8072
        "Audio quality" -> R.string.ui_623b1fca274a
        "Playback" -> R.string.ui_2db1db07062e
        "Player" -> R.string.ui_64aee8c6cbd0
        "Lyrics" -> R.string.ui_e73a4aa7e5e3
        "Queue" -> R.string.ui_3b2fe03e3689
        "Downloads" -> R.string.ui_d5fdc1af021e
        "Storage" -> R.string.ui_a69c4dece144
        "Privacy" -> R.string.ui_54a57c3147c4
        "About" -> R.string.ui_4efca0d10c5f
        "Your library" -> R.string.ui_1ad84286274b
        "Songs" -> R.string.ui_bc6761357960
        "Albums" -> R.string.ui_57bca6e1c6d4
        "Artists" -> R.string.ui_94e3e8a44073
        "Playlists" -> R.string.ui_dcd0a4d2c65a
        "Liked songs" -> R.string.ui_5daa65c4da48
        "Recently played" -> R.string.ui_452ec9f553a3
        "Speed dial" -> R.string.ui_df3c47c67684
        "Quick picks" -> R.string.ui_2c15fdbe1935
        "Your top artists" -> R.string.ui_1b3d54bff75d
        "Top artists" -> R.string.ui_5c6b3cfd048b
        "Top songs" -> R.string.ui_41151e09b7e5
        "From your listening" -> R.string.ui_ac5ba9c891bf
        "Made for you today" -> R.string.ui_095c848218c8
        "From your library" -> R.string.ui_e8f279b001ae
        "New releases" -> R.string.ui_136dfb88fd85
        "Good morning" -> R.string.ui_90a90a48e23d
        "Good afternoon" -> R.string.ui_d325e1bbfd1c
        "Good evening" -> R.string.ui_15a421e4cf76
        "Now playing" -> R.string.ui_0906bc70a0ec
        "Play" -> R.string.ui_436e61016e26
        "Pause" -> R.string.ui_858e4ba7a29f
        "Previous" -> R.string.ui_a57b08a480b8
        "Next" -> R.string.ui_1ff57a29d7c9
        "Shuffle" -> R.string.ui_317cb3a7efad
        "Repeat" -> R.string.ui_b6b7a0065808
        "Like" -> R.string.ui_64f915cb8bfc
        "Unlike" -> R.string.ui_eaa881c359cf
        "Download" -> R.string.ui_d6eafe823591
        "Add to playlist" -> R.string.ui_5d02009485ce
        "Add to queue" -> R.string.ui_34b25c1b963f
        "Play next" -> R.string.ui_994ffab2d071
        "Start radio" -> R.string.ui_3afec4dc1fe0
        "Open artist" -> R.string.ui_1d74bab5b102
        "Open album" -> R.string.ui_2dcf4998e0df
        "Share" -> R.string.ui_29887a5ff984
        "Sleep timer" -> R.string.ui_336c84d8da7c
        "Song info" -> R.string.ui_5b3ff208d946
        "Lyrics offset" -> R.string.ui_ee8d2a728fae
        "Copy log" -> R.string.ui_99d7297aa0b8
        "Audio sources" -> R.string.ui_30035729daea
        "Refresh audio sources" -> R.string.ui_d8fbda55975c
        "Add an add-on" -> R.string.ui_f72a1636e8b1
        "Add source" -> R.string.ui_6ea6c9ec2a76
        "Enabled" -> R.string.ui_92c1cdfdf4cb
        "Disabled" -> R.string.ui_75081b593d15
        "Turned off by the admin for now" -> R.string.ui_69f57519fa3f
        "Paused by its owner for now" -> R.string.ui_98848e65651e
        "Service unavailable" -> R.string.ui_60bbf0436e6d
        "Recommendations" -> R.string.ui_0738ee00b61b
        "Sync listening history" -> R.string.ui_90995efbfe48
        "Pause account sync" -> R.string.ui_471915542d27
        "This device" -> R.string.ui_d052579c6a31
        "Autoplay" -> R.string.ui_892aead95750
        "Smart shuffle" -> R.string.ui_b7310bb4dea2
        "Equalizer" -> R.string.ui_71255f2b6855
        "Clarity" -> R.string.ui_3861619d4ee0
        "Spatial audio" -> R.string.ui_0b4b902b4c0f
        "On Wi-Fi" -> R.string.ui_a40e8e22e2ba
        "On mobile data" -> R.string.ui_aa502f7d048d
        "Lossless" -> R.string.ui_4b86dab6fdea
        "High quality" -> R.string.ui_9720b344df46
        "Hi-Res Lossless" -> R.string.ui_0371821c3fc5
        "Upgrading quality" -> R.string.ui_594bbfd7324d
        "High quality, upgrading" -> R.string.ui_3b0160213316
        "Player layout" -> R.string.ui_bef9404fb65d
        "Player background" -> R.string.ui_917c0ca4a898
        "Player buttons" -> R.string.ui_e089f7778ed4
        "Fonts & readability" -> R.string.ui_8750701cf154
        "Theme" -> R.string.ui_efb52e7172b7
        "Dark" -> R.string.ui_60acc53f13a5
        "Light" -> R.string.ui_dbcd5e7bb7a0
        "System" -> R.string.ui_6725e7bbcd28
        "AMOLED black" -> R.string.ui_4f22ef352892
        "Classic" -> R.string.ui_ae25d6481fc1
        "Apple Music inspired" -> R.string.ui_6bff0539b14d
        "Text size" -> R.string.ui_d68761cc1eb2
        "Regular" -> R.string.ui_b455784ab530
        "Medium" -> R.string.ui_8e588cd18774
        "Bold" -> R.string.ui_94fee62e68e2
        "Imported font" -> R.string.ui_131221e57e9c
        "Import font" -> R.string.ui_1cf7b894de15
        "Sign in" -> R.string.ui_bfd402b2f6f3
        "Sign out" -> R.string.ui_48f0d3d397d4
        "Connected" -> R.string.ui_22965568d22a
        "Not connected" -> R.string.ui_0303e1824670
        "Done" -> R.string.ui_11a6767d5674
        "Close" -> R.string.ui_7d9eb7acb13e
        "Cancel" -> R.string.ui_19766ed6ccb2
        "Save" -> R.string.ui_1509f561f241
        "Delete" -> R.string.ui_e2d0a54968ea
        "Remove" -> R.string.ui_c3812fc4acb8
        "Retry" -> R.string.ui_942087cc2d41
        "Refresh" -> R.string.ui_0e9161011702
        "Clear" -> R.string.ui_83b12c2216ef
        "Back" -> R.string.ui_76900f1bfd16
        "More" -> R.string.ui_d47d7cb0e4f8
        "See all" -> R.string.ui_d7a8c446069a
        "Search settings" -> R.string.ui_255b076dd828
        "Help" -> R.string.ui_b79cac926e0b
        "Offline" -> R.string.ui_a1794783aab7
        "Online" -> R.string.ui_0d21bd52022c
        "Nothing playing" -> R.string.ui_05aed12e5196
        "Your recently played songs will appear here" -> R.string.ui_88dedd349132
        "Pause sending new listens to YouTube Music. Your local history, listening stats and personal recommendations keep updating. Paused listens are never sent later; likes and connected scrobblers keep their own settings." -> R.string.ui_4a8982d05d70
        "Audio" -> R.string.ui_bc1b88907d3b
        "Add-ons" -> R.string.ui_ccfc53fcbd49
        "Interface" -> R.string.ui_c26b3ed4ce70
        "Other devices" -> R.string.ui_4e027dbb70b2
        "Streaming quality" -> R.string.ui_452f50c331a8
        "Loudness" -> R.string.ui_3efdeb9e9b0d
        "Sound" -> R.string.ui_46124e023603
        "Output" -> R.string.ui_b2439bcb8dee
        "Lossless streaming" -> R.string.ui_68319ba84a79
        "Unmetered networks only" -> R.string.ui_75f473969744
        "Layout" -> R.string.ui_a511909161e1
        "Cover art" -> R.string.ui_aff791fbf4bc
        "Controls" -> R.string.ui_799c26913574
        "Account sync" -> R.string.ui_0c14f731fc61
        "See more" -> R.string.ui_bab37d624729
        "Data usage" -> R.string.ui_437c5ebb8258
        "Support" -> R.string.ui_be91940b79f4
        "Audio add-ons" -> R.string.ui_ca3fc732392f
        "Appearance & motion" -> R.string.ui_b2a1ac9af20e
        "Library & downloads" -> R.string.ui_b10e2450d87a
        "Privacy & data" -> R.string.ui_4391d8a99171
        "Added by admin" -> R.string.ui_a4bb4e5d515e
        "Your source" -> R.string.ui_0a7d95fa81ae
        else -> null
    }

fun translated(context: Context, text: String): String = resourceFor(text)?.let(context::getString) ?: text

@Composable
fun tr(text: String): String = resourceFor(text)?.let { stringResource(it) } ?: text

