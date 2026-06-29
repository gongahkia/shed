public struct KillRing: Sendable {
	public let capacity: Int
	private var entries: [String] = []
	private var currentIndex = 0

	public init(capacity: Int = 60) {
		self.capacity = max(1, capacity)
	}

	public var current: String? {
		guard !entries.isEmpty else {
			return nil
		}
		return entries[currentIndex]
	}

	public mutating func push(_ text: String) {
		guard !text.isEmpty else {
			return
		}
		entries.removeAll { $0 == text }
		entries.insert(text, at: 0)
		if entries.count > capacity {
			entries.removeLast(entries.count - capacity)
		}
		currentIndex = 0
	}

	public mutating func rotate() -> String? {
		guard !entries.isEmpty else {
			return nil
		}
		currentIndex = (currentIndex + 1) % entries.count
		return entries[currentIndex]
	}
}
