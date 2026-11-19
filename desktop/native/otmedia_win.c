/*
 * otmedia on Windows: the System Media Transport Controls (the media flyout by the volume,
 * the lock screen, and the keyboard's media keys) for the app's window.
 *
 * Desktop apps reach SMTC through ISystemMediaTransportControlsInterop::GetForWindow. The
 * WinRT interfaces it hands back aren't in MinGW's headers, so they're called here through
 * their vtables by slot. Slots and interface ids follow Windows' metadata (as Wine's
 * include/windows.media.idl records it): slots 0-5 are IUnknown and IInspectable, an
 * interface's own methods start at 6. The ids of the two event handlers are the WinRT
 * parameterized-interface ids, worked out from their type signatures.
 *
 * The same small C API as otmedia_mac.m, called from MediaControls.kt through JNA.
 */
#define COBJMACROS
#include <windows.h>
#include <roapi.h>
#include <winstring.h>
#include <inspectable.h>
#include <eventtoken.h>
#include <systemmediatransportcontrolsinterop.h>
#include <stdlib.h>

#define OT_API __declspec(dllexport)

/* What the controls ask for, as MediaControls.kt reads it. */
enum { OT_PLAY = 1, OT_PAUSE = 2, OT_TOGGLE = 3, OT_NEXT = 4, OT_PREVIOUS = 5, OT_STOP = 6, OT_SEEK = 7 };
typedef void (*ot_media_cb)(int command, long long value);

static const GUID IID_SMTC = {0x99fa3ff4, 0x1742, 0x42a6, {0x90, 0x2e, 0x08, 0x7d, 0x41, 0xf9, 0x65, 0xec}};
static const GUID IID_SMTC2 = {0xea98d2f6, 0x7f3c, 0x4af2, {0xa5, 0x86, 0x72, 0x88, 0x98, 0x08, 0xef, 0xb1}};
static const GUID IID_TIMELINE = {0x5125316a, 0xc3a2, 0x475b, {0x85, 0x07, 0x93, 0x53, 0x4d, 0xc8, 0x8f, 0x15}};
static const GUID IID_URI_FACTORY = {0x44a9796f, 0x723e, 0x4fdf, {0xa2, 0x18, 0x03, 0x3e, 0x75, 0xb0, 0xc0, 0x84}};
static const GUID IID_STREAM_REF_STATICS = {0x857309dc, 0x3fbf, 0x4e7d, {0x98, 0x6f, 0xef, 0x3b, 0x1a, 0x07, 0xa9, 0x64}};
/* TypedEventHandler<SystemMediaTransportControls, SystemMediaTransportControlsButtonPressedEventArgs> */
static const GUID IID_BUTTON_HANDLER = {0x0557e996, 0x7b23, 0x5bae, {0xaa, 0x81, 0xea, 0x0d, 0x67, 0x11, 0x43, 0xa4}};
/* TypedEventHandler<SystemMediaTransportControls, PlaybackPositionChangeRequestedEventArgs> */
static const GUID IID_POSITION_HANDLER = {0x44e34f15, 0xbdc0, 0x50a7, {0xac, 0xe4, 0x39, 0xe9, 0x1f, 0xb7, 0x53, 0xf1}};
static const GUID IID_SMTC_INTEROP = {0xddb0472d, 0xc911, 0x4a1f, {0x86, 0xd9, 0xdc, 0x3d, 0x71, 0xa9, 0x5f, 0x5a}};
static const GUID IID_UNKNOWN = {0x00000000, 0x0000, 0x0000, {0xc0, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x46}};
static const GUID IID_AGILE = {0x94ea2b94, 0xe9cc, 0x49e0, {0xc0, 0xff, 0xee, 0x64, 0xca, 0x8f, 0x5b, 0x90}};

/* The method in slot [i] of [obj]'s vtable, as type [T]. */
#define SLOT(obj, i, T) ((T)((*(void ***)(obj))[i]))
typedef HRESULT (STDMETHODCALLTYPE *Get)(void *, void **);
typedef HRESULT (STDMETHODCALLTYPE *PutInt)(void *, INT32);
typedef HRESULT (STDMETHODCALLTYPE *PutBool)(void *, boolean);
typedef HRESULT (STDMETHODCALLTYPE *PutString)(void *, HSTRING);
typedef HRESULT (STDMETHODCALLTYPE *PutObject)(void *, void *);
typedef HRESULT (STDMETHODCALLTYPE *PutTime)(void *, INT64);
typedef HRESULT (STDMETHODCALLTYPE *Call)(void *);
typedef HRESULT (STDMETHODCALLTYPE *AddHandler)(void *, void *, EventRegistrationToken *);
typedef HRESULT (STDMETHODCALLTYPE *CreateFrom)(void *, void *, void **);
typedef HRESULT (STDMETHODCALLTYPE *MakeUri)(void *, HSTRING, void **);
typedef HRESULT (STDMETHODCALLTYPE *GetInt)(void *, INT32 *);
typedef HRESULT (STDMETHODCALLTYPE *GetTime)(void *, INT64 *);

static void release(void *p) { if (p) ((IUnknown *)p)->lpVtbl->Release((IUnknown *)p); }

static ot_media_cb g_cb;
static void *g_smtc;   /* ISystemMediaTransportControls */
static void *g_smtc2;  /* ISystemMediaTransportControls2, when Windows has it */
static CRITICAL_SECTION g_lock;
static int g_lock_ready;

/* ---- An event handler, as a small COM object ------------------------------------------ */

typedef struct Handler Handler;
typedef struct {
    HRESULT (STDMETHODCALLTYPE *QueryInterface)(Handler *, REFIID, void **);
    ULONG (STDMETHODCALLTYPE *AddRef)(Handler *);
    ULONG (STDMETHODCALLTYPE *Release)(Handler *);
    HRESULT (STDMETHODCALLTYPE *Invoke)(Handler *, void *sender, void *args);
} HandlerVtbl;
struct Handler {
    const HandlerVtbl *lpVtbl;
    LONG refs;
    const GUID *iid;
};

static HRESULT STDMETHODCALLTYPE handler_qi(Handler *This, REFIID riid, void **out) {
    if (IsEqualGUID(riid, &IID_UNKNOWN) || IsEqualGUID(riid, &IID_AGILE) || IsEqualGUID(riid, This->iid)) {
        *out = This;
        InterlockedIncrement(&This->refs);
        return S_OK;
    }
    *out = NULL;
    return E_NOINTERFACE;
}
static ULONG STDMETHODCALLTYPE handler_addref(Handler *This) { return (ULONG)InterlockedIncrement(&This->refs); }
static ULONG STDMETHODCALLTYPE handler_release(Handler *This) {
    LONG n = InterlockedDecrement(&This->refs);
    if (n == 0) free(This);
    return (ULONG)n;
}

/* SystemMediaTransportControlsButton: Play 0, Pause 1, Stop 2, Next 6, Previous 7. */
static HRESULT STDMETHODCALLTYPE on_button(Handler *This, void *sender, void *args) {
    (void)This; (void)sender;
    INT32 button = -1;
    if (args && SUCCEEDED(SLOT(args, 6, GetInt)(args, &button)) && g_cb) {
        switch (button) {
            case 0: g_cb(OT_PLAY, 0); break;
            case 1: g_cb(OT_PAUSE, 0); break;
            case 2: g_cb(OT_STOP, 0); break;
            case 6: g_cb(OT_NEXT, 0); break;
            case 7: g_cb(OT_PREVIOUS, 0); break;
            default: break;
        }
    }
    return S_OK;
}

/* A seek from the flyout or lock screen: the time asked for, in 100 ns units. */
static HRESULT STDMETHODCALLTYPE on_position(Handler *This, void *sender, void *args) {
    (void)This; (void)sender;
    INT64 at = 0;
    if (args && SUCCEEDED(SLOT(args, 6, GetTime)(args, &at)) && g_cb) g_cb(OT_SEEK, at / 10000);
    return S_OK;
}

static const HandlerVtbl BUTTON_VTBL = {handler_qi, handler_addref, handler_release, on_button};
static const HandlerVtbl POSITION_VTBL = {handler_qi, handler_addref, handler_release, on_position};

static Handler *handler(const HandlerVtbl *vtbl, const GUID *iid) {
    Handler *h = (Handler *)calloc(1, sizeof(Handler));
    if (!h) return NULL;
    h->lpVtbl = vtbl;
    h->refs = 1;
    h->iid = iid;
    return h;
}

/* ---- Strings ---------------------------------------------------------------------------- */

static HSTRING hstring(const char *utf8) {
    HSTRING out = NULL;
    if (!utf8) utf8 = "";
    int n = MultiByteToWideChar(CP_UTF8, 0, utf8, -1, NULL, 0);
    if (n <= 0) return NULL;
    WCHAR *wide = (WCHAR *)malloc(sizeof(WCHAR) * n);
    if (!wide) return NULL;
    MultiByteToWideChar(CP_UTF8, 0, utf8, -1, wide, n);
    WindowsCreateString(wide, (UINT32)(n - 1), &out);
    free(wide);
    return out;
}

static HRESULT factory(const WCHAR *name, const GUID *iid, void **out) {
    HSTRING cls = NULL;
    HRESULT hr = WindowsCreateString(name, (UINT32)wcslen(name), &cls);
    if (FAILED(hr)) return hr;
    hr = RoGetActivationFactory(cls, iid, out);
    WindowsDeleteString(cls);
    return hr;
}

static void ready(void) {
    if (!g_lock_ready) { InitializeCriticalSection(&g_lock); g_lock_ready = 1; }
    /* Each calling thread joins the multi-threaded apartment; S_FALSE when it already has. */
    RoInitialize(RO_INIT_MULTITHREADED);
}

/* ---- The API ---------------------------------------------------------------------------- */

/* Takes the controls for [hwnd], calling [cb] for what they ask. 0 when ready, else the HRESULT. */
OT_API int otm_start(void *hwnd, ot_media_cb cb) {
    ready();
    if (g_smtc) return 0;
    g_cb = cb;
    ISystemMediaTransportControlsInterop *interop = NULL;
    HRESULT hr = factory(L"Windows.Media.SystemMediaTransportControls", &IID_SMTC_INTEROP, (void **)&interop);
    if (FAILED(hr)) return (int)hr;
    void *smtc = NULL;
    hr = interop->lpVtbl->GetForWindow(interop, (HWND)hwnd, &IID_SMTC, &smtc);
    release(interop);
    if (FAILED(hr) || !smtc) return (int)hr;
    SLOT(smtc, 11, PutBool)(smtc, TRUE);   /* IsEnabled */
    SLOT(smtc, 13, PutBool)(smtc, TRUE);   /* IsPlayEnabled */
    SLOT(smtc, 15, PutBool)(smtc, TRUE);   /* IsStopEnabled */
    SLOT(smtc, 17, PutBool)(smtc, TRUE);   /* IsPauseEnabled */
    SLOT(smtc, 25, PutBool)(smtc, TRUE);   /* IsPreviousEnabled */
    SLOT(smtc, 27, PutBool)(smtc, TRUE);   /* IsNextEnabled */
    EventRegistrationToken token;
    Handler *buttons = handler(&BUTTON_VTBL, &IID_BUTTON_HANDLER);
    if (buttons) { SLOT(smtc, 32, AddHandler)(smtc, buttons, &token); buttons->lpVtbl->Release(buttons); }
    void *smtc2 = NULL;
    if (SUCCEEDED(((IUnknown *)smtc)->lpVtbl->QueryInterface((IUnknown *)smtc, &IID_SMTC2, &smtc2)) && smtc2) {
        Handler *seeks = handler(&POSITION_VTBL, &IID_POSITION_HANDLER);
        if (seeks) { SLOT(smtc2, 13, AddHandler)(smtc2, seeks, &token); seeks->lpVtbl->Release(seeks); }
    }
    EnterCriticalSection(&g_lock);
    g_smtc = smtc;
    g_smtc2 = smtc2;
    LeaveCriticalSection(&g_lock);
    return 0;
}

/* The song shown: its title, artist, album and cover (an https address, or null). */
OT_API void otm_metadata(const char *title, const char *artist, const char *album, const char *art_url) {
    ready();
    (void)album; /* AlbumTitle is on IMusicDisplayProperties2; the artist line carries enough. */
    EnterCriticalSection(&g_lock);
    void *smtc = g_smtc;
    if (smtc) ((IUnknown *)smtc)->lpVtbl->AddRef((IUnknown *)smtc);
    LeaveCriticalSection(&g_lock);
    if (!smtc) return;
    void *updater = NULL;
    if (SUCCEEDED(SLOT(smtc, 8, Get)(smtc, &updater)) && updater) {
        SLOT(updater, 7, PutInt)(updater, 1);  /* Type = Music */
        void *music = NULL;
        if (SUCCEEDED(SLOT(updater, 12, Get)(updater, &music)) && music) {
            HSTRING t = hstring(title), a = hstring(artist);
            SLOT(music, 7, PutString)(music, t);   /* Title */
            SLOT(music, 11, PutString)(music, a);  /* Artist */
            WindowsDeleteString(t);
            WindowsDeleteString(a);
            release(music);
        }
        void *thumb = NULL;
        if (art_url && art_url[0]) {
            void *uris = NULL, *refs = NULL, *uri = NULL;
            HSTRING u = hstring(art_url);
            if (SUCCEEDED(factory(L"Windows.Foundation.Uri", &IID_URI_FACTORY, &uris)) &&
                SUCCEEDED(SLOT(uris, 6, MakeUri)(uris, u, &uri)) &&
                SUCCEEDED(factory(L"Windows.Storage.Streams.RandomAccessStreamReference", &IID_STREAM_REF_STATICS, &refs))) {
                SLOT(refs, 7, CreateFrom)(refs, uri, &thumb);  /* CreateFromUri */
            }
            WindowsDeleteString(u);
            release(uri);
            release(refs);
            release(uris);
        }
        SLOT(updater, 11, PutObject)(updater, thumb);  /* Thumbnail, or none */
        release(thumb);
        SLOT(updater, 17, Call)(updater);               /* Update */
        release(updater);
    }
    release(smtc);
}

/* Playing (2), paused (1) or stopped (0), where in the song, and how long it is. */
OT_API void otm_state(int state, long long position_ms, long long duration_ms) {
    ready();
    EnterCriticalSection(&g_lock);
    void *smtc = g_smtc, *smtc2 = g_smtc2;
    if (smtc) ((IUnknown *)smtc)->lpVtbl->AddRef((IUnknown *)smtc);
    if (smtc2) ((IUnknown *)smtc2)->lpVtbl->AddRef((IUnknown *)smtc2);
    LeaveCriticalSection(&g_lock);
    if (!smtc) return;
    /* MediaPlaybackStatus: Stopped 2, Playing 3, Paused 4. */
    SLOT(smtc, 7, PutInt)(smtc, state == 2 ? 3 : state == 1 ? 4 : 2);
    if (smtc2 && duration_ms > 0) {
        HSTRING cls = NULL;
        IInspectable *made = NULL;
        void *timeline = NULL;
        const WCHAR *name = L"Windows.Media.SystemMediaTransportControlsTimelineProperties";
        if (SUCCEEDED(WindowsCreateString(name, (UINT32)wcslen(name), &cls)) && SUCCEEDED(RoActivateInstance(cls, &made)) &&
            SUCCEEDED(made->lpVtbl->QueryInterface(made, &IID_TIMELINE, &timeline))) {
            SLOT(timeline, 7, PutTime)(timeline, 0);                        /* StartTime */
            SLOT(timeline, 9, PutTime)(timeline, duration_ms * 10000);      /* EndTime */
            SLOT(timeline, 11, PutTime)(timeline, 0);                       /* MinSeekTime */
            SLOT(timeline, 13, PutTime)(timeline, duration_ms * 10000);     /* MaxSeekTime */
            SLOT(timeline, 15, PutTime)(timeline, position_ms * 10000);     /* Position */
            SLOT(smtc2, 12, PutObject)(smtc2, timeline);                    /* UpdateTimelineProperties */
        }
        release(timeline);
        release(made);
        if (cls) WindowsDeleteString(cls);
    }
    release(smtc2);
    release(smtc);
}

/* Gives the controls up: the flyout no longer shows the app. */
OT_API void otm_stop(void) {
    ready();
    EnterCriticalSection(&g_lock);
    void *smtc = g_smtc, *smtc2 = g_smtc2;
    g_smtc = NULL;
    g_smtc2 = NULL;
    LeaveCriticalSection(&g_lock);
    if (smtc) {
        SLOT(smtc, 7, PutInt)(smtc, 0);       /* Closed */
        SLOT(smtc, 11, PutBool)(smtc, FALSE); /* IsEnabled */
    }
    release(smtc2);
    release(smtc);
}
