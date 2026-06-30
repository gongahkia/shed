import Foundation

/// Purpose: Selects the timing curve for layout animations.
public enum AnimationCurve: String, Codable, Equatable, Sendable {
    case linear
    case easeIn
    case easeOut
    case easeInOut
}
