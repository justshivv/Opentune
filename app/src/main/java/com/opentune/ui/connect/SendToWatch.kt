package com.opentune.ui.connect

import android.content.Context
import com.opentune.data.Pills
import com.opentune.data.connect.Connect
import com.opentune.data.connect.KnownWatch
import com.opentune.data.connect.WatchRequest
import com.opentune.data.model.Song
import com.opentune.i18n.Tr

/**
 * "Send to watch": asks a watch in the Connect group to download a song or a playlist or album
 * itself, over its own Wi-Fi. The request waits on the relays until the watch is next online
 * (see [com.opentune.data.connect.WatchMail]).
 */
object SendToWatch {
    /** The menu label for [watch]: "Send to watch", or with its name when there's more than one. */
    fun label(watch: KnownWatch, all: List<KnownWatch>): String =
        if (all.size <= 1) Tr.t("Send to watch") else Tr.f("Send to {0}", watch.name)

    fun song(context: Context, watch: KnownWatch, song: Song) {
        Connect.sendToWatch(watch.id, WatchRequest.SONG, song.videoId, song.title, song.artist, song.thumbnailUrl, song.durationText)
        said(context, watch)
    }

    fun collection(context: Context, watch: KnownWatch, browseId: String, title: String, subtitle: String?, thumbnailUrl: String?) {
        Connect.sendToWatch(watch.id, WatchRequest.COLLECTION, browseId, title, subtitle, thumbnailUrl)
        said(context, watch)
    }

    private fun said(context: Context, watch: KnownWatch) {
        val here = Connect.view.value?.devices?.any { it.id == watch.id } == true
        Pills.done(
            if (here) Tr.f("Sent to {0}. It downloads over Wi-Fi.", watch.name)
            else Tr.f("Sent to {0}. It downloads once it's online.", watch.name),
            context,
        )
    }
}
