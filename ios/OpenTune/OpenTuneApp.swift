import OpenTuneCore
import SwiftUI

@main
struct OpenTuneApp: App {
    @StateObject private var library: Library
    @StateObject private var player: Player

    init() {
        let library = Library()
        _library = StateObject(wrappedValue: library)
        _player = StateObject(wrappedValue: Player(library: library))
        Task {
            let lang = Locale.current.language.languageCode?.identifier ?? "en"
            let region = Locale.current.region?.identifier ?? "US"
            await Innertube.shared.setLocale(language: lang, region: region)
        }
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .modifier(AppReadability())
                .environmentObject(library)
                .environmentObject(player)
                .preferredColorScheme(.dark)
                .tint(Theme.accent)
        }
    }
}
