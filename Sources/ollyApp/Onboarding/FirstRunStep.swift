enum FirstRunStep: Int, CaseIterable {
    case welcome
    case accessibility
    case display
    case preset
    case telemetry
    case cheatsheet
    case done

    var title: String {
        switch self {
        case .welcome: return L10n.s("Welcome", "first-run welcome step")
        case .accessibility: return L10n.s("Accessibility", "first-run accessibility step")
        case .display: return L10n.s("Displays", "first-run displays step")
        case .preset: return L10n.s("Preset", "first-run preset step")
        case .telemetry: return L10n.s("Telemetry", "first-run telemetry step")
        case .cheatsheet: return L10n.s("Keybinds", "first-run keybinds step")
        case .done: return L10n.s("Done", "first-run done step")
        }
    }
}
