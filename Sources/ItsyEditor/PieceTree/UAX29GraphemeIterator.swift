public struct UAX29GraphemeIterator: Sequence, IteratorProtocol {
	private let bytes: UnsafeBufferPointer<UInt8>
	private var nextOffset: Int
	private var clusterStart: Int
	private var previous: Scalar?
	private var previousZWJHasExtendedPictographicPrefix: Bool
	private var state: ClusterState
	private var isDone: Bool

	public init(bytes: UnsafeBufferPointer<UInt8>) {
		self.bytes = bytes
		nextOffset = 0
		clusterStart = 0
		previous = nil
		previousZWJHasExtendedPictographicPrefix = false
		state = ClusterState()
		isDone = bytes.isEmpty
		if !bytes.isEmpty {
			let first = Self.decodeScalar(in: bytes, at: 0)
			let zwjPrefix = state.extendedPictographicSuffix
			state.consume(first)
			nextOffset = first.end
			clusterStart = first.start
			previous = first
			previousZWJHasExtendedPictographicPrefix = first.property == .zwj && zwjPrefix
		}
	}

	public func makeIterator() -> UAX29GraphemeIterator {
		self
	}

	public mutating func next() -> Range<Int>? {
		guard !isDone else {
			return nil
		}
		while nextOffset < bytes.count {
			guard let previousScalar = previous else {
				isDone = true
				return nil
			}
			let current = Self.decodeScalar(in: bytes, at: nextOffset)
			let shouldBreak = Self.shouldBreak(
				between: previousScalar,
				and: current,
				state: state,
				previousZWJHasExtendedPictographicPrefix: previousZWJHasExtendedPictographicPrefix
			)
			if shouldBreak {
				let result = clusterStart ..< current.start
				state = ClusterState()
				clusterStart = current.start
				let zwjPrefix = state.extendedPictographicSuffix
				state.consume(current)
				previous = current
				previousZWJHasExtendedPictographicPrefix = current.property == .zwj && zwjPrefix
				nextOffset = current.end
				return result
			}
			let zwjPrefix = state.extendedPictographicSuffix
			state.consume(current)
			previous = current
			previousZWJHasExtendedPictographicPrefix = current.property == .zwj && zwjPrefix
			nextOffset = current.end
		}
		isDone = true
		return clusterStart ..< bytes.count
	}

	public static func boundaries(in bytes: UnsafeBufferPointer<UInt8>) -> [Int] {
		if isASCIIWithoutCR(bytes) {
			return Array(0 ... bytes.count)
		}
		var result = [0]
		var iterator = UAX29GraphemeIterator(bytes: bytes)
		while let range = iterator.next() {
			result.append(range.upperBound)
		}
		return result
	}

	public static func graphemeCount(in bytes: UnsafeBufferPointer<UInt8>) -> Int {
		if isASCIIWithoutCR(bytes) {
			return bytes.count
		}
		var count = 0
		var iterator = UAX29GraphemeIterator(bytes: bytes)
		while iterator.next() != nil {
			count += 1
		}
		return count
	}

	public static func graphemeIndex(in bytes: UnsafeBufferPointer<UInt8>, before offset: Int) -> Int {
		precondition((0 ... bytes.count).contains(offset), "grapheme offset out of bounds")
		if isASCIIWithoutCR(bytes) {
			return offset
		}
		var count = 0
		var iterator = UAX29GraphemeIterator(bytes: bytes)
		while let range = iterator.next() {
			if offset < range.upperBound {
				return count
			}
			count += 1
		}
		return count
	}

	private static func isASCIIWithoutCR(_ bytes: UnsafeBufferPointer<UInt8>) -> Bool {
		for byte in bytes {
			if byte >= 0x80 || byte == 0x0D {
				return false
			}
		}
		return true
	}

	private static func shouldBreak(
		between previous: Scalar,
		and current: Scalar,
		state: ClusterState,
		previousZWJHasExtendedPictographicPrefix: Bool
	) -> Bool {
		if previous.property == .cr, current.property == .lf {
			return false
		}
		if previous.isControlBoundary || current.isControlBoundary {
			return true
		}
		switch previous.property {
		case .l:
			if current.property == .l || current.property == .v || current.property == .lv || current.property == .lvt {
				return false
			}
		case .lv, .v:
			if current.property == .v || current.property == .t {
				return false
			}
		case .lvt, .t:
			if current.property == .t {
				return false
			}
		default:
			break
		}
		if current.property == .extend || current.property == .zwj {
			return false
		}
		if current.property == .spacingMark {
			return false
		}
		if previous.property == .prepend {
			return false
		}
		if current.indicConjunctBreak == .consonant, state.indic == .linkerSeen {
			return false
		}
		if previous.property == .zwj, previousZWJHasExtendedPictographicPrefix, current.isExtendedPictographic {
			return false
		}
		if previous.property == .regionalIndicator, current.property == .regionalIndicator, state.regionalIndicatorRun % 2 == 1 {
			return false
		}
		return true
	}

	private static func decodeScalar(in bytes: UnsafeBufferPointer<UInt8>, at offset: Int) -> Scalar {
		let first = bytes[offset]
		if first < 0x80 {
			return Scalar(value: UInt32(first), start: offset, end: offset + 1)
		}
		if first & 0xE0 == 0xC0, offset + 1 < bytes.count {
			let second = bytes[offset + 1]
			if second & 0xC0 == 0x80 {
				let value = (UInt32(first & 0x1F) << 6) | UInt32(second & 0x3F)
				if value >= 0x80 {
					return Scalar(value: value, start: offset, end: offset + 2)
				}
			}
		}
		if first & 0xF0 == 0xE0, offset + 2 < bytes.count {
			let second = bytes[offset + 1]
			let third = bytes[offset + 2]
			if second & 0xC0 == 0x80, third & 0xC0 == 0x80 {
				let value = (UInt32(first & 0x0F) << 12) | (UInt32(second & 0x3F) << 6) | UInt32(third & 0x3F)
				if value >= 0x800, !(0xD800 ... 0xDFFF).contains(value) {
					return Scalar(value: value, start: offset, end: offset + 3)
				}
			}
		}
		if first & 0xF8 == 0xF0, offset + 3 < bytes.count {
			let second = bytes[offset + 1]
			let third = bytes[offset + 2]
			let fourth = bytes[offset + 3]
			if second & 0xC0 == 0x80, third & 0xC0 == 0x80, fourth & 0xC0 == 0x80 {
				let value = (UInt32(first & 0x07) << 18) | (UInt32(second & 0x3F) << 12) | (UInt32(third & 0x3F) << 6) | UInt32(fourth & 0x3F)
				if (0x10000 ... 0x10FFFF).contains(value) {
					return Scalar(value: value, start: offset, end: offset + 4)
				}
			}
		}
		return Scalar(value: 0xFFFD, start: offset, end: offset + 1)
	}

	private struct Scalar {
		var value: UInt32
		var start: Int
		var end: Int
		var property: UAX29GraphemeBreakProperty
		var indicConjunctBreak: UAX29IndicConjunctBreak
		var isExtendedPictographic: Bool

		init(value: UInt32, start: Int, end: Int) {
			self.value = value
			self.start = start
			self.end = end
			property = UAX29GraphemeTables.graphemeBreakProperty(for: value)
			indicConjunctBreak = UAX29GraphemeTables.indicConjunctBreak(for: value)
			isExtendedPictographic = UAX29GraphemeTables.isExtendedPictographic(value)
		}

		var isControlBoundary: Bool {
			property == .cr || property == .lf || property == .control
		}
	}

	private struct ClusterState {
		var extendedPictographicSuffix = false
		var indic = IndicState.none
		var regionalIndicatorRun = 0

		mutating func consume(_ scalar: Scalar) {
			if scalar.isExtendedPictographic {
				extendedPictographicSuffix = true
			} else if scalar.property != .extend {
				extendedPictographicSuffix = false
			}
			switch scalar.indicConjunctBreak {
			case .consonant:
				indic = .consonantSeen
			case .extend:
				break
			case .linker:
				if indic != .none {
					indic = .linkerSeen
				}
			case .none:
				indic = .none
			}
			if scalar.property == .regionalIndicator {
				regionalIndicatorRun += 1
			} else {
				regionalIndicatorRun = 0
			}
		}
	}

	private enum IndicState {
		case none
		case consonantSeen
		case linkerSeen
	}
}
