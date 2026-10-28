import SwiftUI

/** Local appearance preferences; shared by the app and its player. */
struct AppearanceView: View {
    @AppStorage("appearance.font") private var font = "system"
    @AppStorage("appearance.weight") private var weight = "medium"
    @AppStorage("player.background") private var background = "artwork"
    @AppStorage("player.lyricsPreview") private var lyricsPreview = true

    var body: some View {
        Form {
            Section("Fonts & readability") {
                Picker("Font", selection: $font) {
                    Text("System sans").tag("system")
                    Text("Rounded").tag("rounded")
                    Text("Serif").tag("serif")
                    Text("Monospace").tag("monospaced")
                }
                Picker("Text weight", selection: $weight) {
                    Text("Regular").tag("regular")
                    Text("Medium").tag("medium")
                    Text("Bold").tag("bold")
                }
                Text("OpenTune").font(.system(size: 28, weight: chosenWeight, design: chosenDesign))
            }
            Section("Player") {
                Picker("Background", selection: $background) {
                    Text("Artwork blur").tag("artwork")
                    Text("Gradient").tag("gradient")
                    Text("AMOLED black").tag("amoled")
                }
                Toggle("Lyrics preview", isOn: $lyricsPreview)
            }
        }
        .navigationTitle("Appearance")
    }

    private var chosenDesign: Font.Design { appFontDesign(font) }
    private var chosenWeight: Font.Weight { textWeight(weight) }
}

func appFontDesign(_ name: String) -> Font.Design {
    switch name { case "rounded": return .rounded; case "serif": return .serif; case "monospaced": return .monospaced; default: return .default }
}
func textWeight(_ name: String) -> Font.Weight {
    switch name { case "bold": return .bold; case "regular": return .regular; default: return .medium }
}

struct AppReadability: ViewModifier {
    @AppStorage("appearance.font") private var font = "system"
    @AppStorage("appearance.weight") private var weight = "medium"
    func body(content: Content) -> some View {
        if #available(iOS 16.1, *) {
            content.fontDesign(appFontDesign(font)).fontWeight(textWeight(weight))
        } else {
            content.font(.system(.body, design: appFontDesign(font))).fontWeight(textWeight(weight))
        }
    }
}
