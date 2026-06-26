public struct LayoutEngineCapabilities: Codable, Equatable, Hashable, OptionSet, Sendable {
    public let rawValue: UInt64

    public init(rawValue: UInt64) {
        self.rawValue = rawValue
    }

    public static let supportsManualSplits = LayoutEngineCapabilities(rawValue: 1 << 0)
    public static let supportsResizing = LayoutEngineCapabilities(rawValue: 1 << 1)
    public static let supportsFloatingMix = LayoutEngineCapabilities(rawValue: 1 << 2)
}
