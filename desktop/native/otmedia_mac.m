/*
 * otmedia on macOS: Now Playing in Control Centre and on the menu bar, and the keyboard's
 * media keys, through MPNowPlayingInfoCenter and MPRemoteCommandCenter.
 *
 * The same small C API as otmedia_win.c, called from MediaControls.kt through JNA. Commands
 * arrive on the main thread and are passed to the callback as they come.
 */
#import <Foundation/Foundation.h>
#import <AppKit/AppKit.h>
#import <MediaPlayer/MediaPlayer.h>

#define OT_API __attribute__((visibility("default")))

enum { OT_PLAY = 1, OT_PAUSE = 2, OT_TOGGLE = 3, OT_NEXT = 4, OT_PREVIOUS = 5, OT_STOP = 6, OT_SEEK = 7 };
typedef void (*ot_media_cb)(int command, long long value);

static ot_media_cb g_cb;
static BOOL g_started;
static NSMutableDictionary *g_info;
static NSString *g_art_url;

static void on_main(dispatch_block_t block) {
    if ([NSThread isMainThread]) block(); else dispatch_async(dispatch_get_main_queue(), block);
}

static MPRemoteCommandHandlerStatus deliver(int command, long long value) {
    if (g_cb) g_cb(command, value);
    return MPRemoteCommandHandlerStatusSuccess;
}

/* Takes Now Playing for the app, calling [cb] for what it asks. 0 when ready. */
OT_API int otm_start(void *window, ot_media_cb cb) {
    (void)window;
    g_cb = cb;
    if (g_started) return 0;
    g_started = YES;
    on_main(^{
        g_info = [NSMutableDictionary dictionary];
        MPRemoteCommandCenter *c = [MPRemoteCommandCenter sharedCommandCenter];
        [c.playCommand addTargetWithHandler:^MPRemoteCommandHandlerStatus(MPRemoteCommandEvent *e) { return deliver(OT_PLAY, 0); }];
        [c.pauseCommand addTargetWithHandler:^MPRemoteCommandHandlerStatus(MPRemoteCommandEvent *e) { return deliver(OT_PAUSE, 0); }];
        [c.togglePlayPauseCommand addTargetWithHandler:^MPRemoteCommandHandlerStatus(MPRemoteCommandEvent *e) { return deliver(OT_TOGGLE, 0); }];
        [c.stopCommand addTargetWithHandler:^MPRemoteCommandHandlerStatus(MPRemoteCommandEvent *e) { return deliver(OT_STOP, 0); }];
        [c.nextTrackCommand addTargetWithHandler:^MPRemoteCommandHandlerStatus(MPRemoteCommandEvent *e) { return deliver(OT_NEXT, 0); }];
        [c.previousTrackCommand addTargetWithHandler:^MPRemoteCommandHandlerStatus(MPRemoteCommandEvent *e) { return deliver(OT_PREVIOUS, 0); }];
        [c.changePlaybackPositionCommand addTargetWithHandler:^MPRemoteCommandHandlerStatus(MPRemoteCommandEvent *e) {
            MPChangePlaybackPositionCommandEvent *p = (MPChangePlaybackPositionCommandEvent *)e;
            return deliver(OT_SEEK, (long long)(p.positionTime * 1000.0));
        }];
        c.playCommand.enabled = YES;
        c.pauseCommand.enabled = YES;
        c.togglePlayPauseCommand.enabled = YES;
        c.nextTrackCommand.enabled = YES;
        c.previousTrackCommand.enabled = YES;
        c.changePlaybackPositionCommand.enabled = YES;
    });
    return 0;
}

static NSString *text(const char *s) {
    return s ? [NSString stringWithUTF8String:s] ?: @"" : @"";
}

/* The song shown: its title, artist, album and cover (an https address, or null). */
OT_API void otm_metadata(const char *title, const char *artist, const char *album, const char *art_url) {
    NSString *t = text(title), *a = text(artist), *al = text(album);
    NSString *art = art_url && art_url[0] ? text(art_url) : nil;
    on_main(^{
        if (!g_info) return;
        g_info[MPMediaItemPropertyTitle] = t;
        g_info[MPMediaItemPropertyArtist] = a;
        g_info[MPMediaItemPropertyAlbumTitle] = al;
        [g_info removeObjectForKey:MPMediaItemPropertyArtwork];
        g_art_url = art;
        [MPNowPlayingInfoCenter defaultCenter].nowPlayingInfo = g_info;
        if (!art) return;
        // The cover, fetched in the background and shown if the song is still the one playing.
        NSURL *url = [NSURL URLWithString:art];
        if (!url) return;
        [[[NSURLSession sharedSession] dataTaskWithURL:url completionHandler:^(NSData *data, NSURLResponse *r, NSError *err) {
            if (!data || err) return;
            NSImage *image = [[NSImage alloc] initWithData:data];
            if (!image) return;
            on_main(^{
                if (!g_info || ![g_art_url isEqualToString:art]) return;
                g_info[MPMediaItemPropertyArtwork] = [[MPMediaItemArtwork alloc] initWithBoundsSize:image.size requestHandler:^NSImage *(CGSize size) { return image; }];
                [MPNowPlayingInfoCenter defaultCenter].nowPlayingInfo = g_info;
            });
        }] resume];
    });
}

/* Playing (2), paused (1) or stopped (0), where in the song, and how long it is. */
OT_API void otm_state(int state, long long position_ms, long long duration_ms) {
    on_main(^{
        if (!g_info) return;
        g_info[MPNowPlayingInfoPropertyElapsedPlaybackTime] = @(position_ms / 1000.0);
        if (duration_ms > 0) g_info[MPMediaItemPropertyPlaybackDuration] = @(duration_ms / 1000.0);
        g_info[MPNowPlayingInfoPropertyPlaybackRate] = @(state == 2 ? 1.0 : 0.0);
        MPNowPlayingInfoCenter *center = [MPNowPlayingInfoCenter defaultCenter];
        center.nowPlayingInfo = g_info;
        center.playbackState = state == 2 ? MPNowPlayingPlaybackStatePlaying
            : state == 1 ? MPNowPlayingPlaybackStatePaused : MPNowPlayingPlaybackStateStopped;
    });
}

/* Gives Now Playing up. */
OT_API void otm_stop(void) {
    on_main(^{
        MPRemoteCommandCenter *c = [MPRemoteCommandCenter sharedCommandCenter];
        for (MPRemoteCommand *command in @[c.playCommand, c.pauseCommand, c.togglePlayPauseCommand, c.stopCommand,
                                           c.nextTrackCommand, c.previousTrackCommand, c.changePlaybackPositionCommand]) {
            [command removeTarget:nil];
        }
        [MPNowPlayingInfoCenter defaultCenter].nowPlayingInfo = nil;
        [MPNowPlayingInfoCenter defaultCenter].playbackState = MPNowPlayingPlaybackStateStopped;
        g_info = nil;
        g_started = NO;
    });
}
