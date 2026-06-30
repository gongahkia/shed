import Foundation

enum OverlayKind: String, CaseIterable, Hashable {
    case altTab
    case cheatsheet
    case commandPalette
    case dragSnap
    case focusRing
    case grid
    case overview
}

@MainActor
final class OverlayRegistry {
    static let shared = OverlayRegistry()

    private var activeKinds: Set<OverlayKind> = []

    var active: Set<OverlayKind> {
        activeKinds
    }

    func register(_ kind: OverlayKind, hiding hiddenKinds: Set<OverlayKind> = []) -> Set<OverlayKind> {
        let hidden = activeKinds.intersection(hiddenKinds)
        activeKinds.subtract(hiddenKinds)
        activeKinds.insert(kind)
        return hidden
    }

    func unregister(_ kind: OverlayKind) {
        activeKinds.remove(kind)
    }

    func isActive(_ kind: OverlayKind) -> Bool {
        activeKinds.contains(kind)
    }
}
