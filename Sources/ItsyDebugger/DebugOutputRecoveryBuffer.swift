import Foundation
import ItsyDAP

public struct DebugOutputRecoveryEntry: Equatable, Sendable {
	public let sequence: Int
	public let body: DAPOutputEventBody

	public init(sequence: Int, body: DAPOutputEventBody) {
		self.sequence = sequence
		self.body = body
	}
}

public actor DebugOutputRecoveryBuffer {
	private let maximumBytes: Int
	private var entries: [DebugOutputRecoveryEntry] = []
	private var storedBytes = 0

	public init(maximumBytes: Int = 262_144) {
		self.maximumBytes = max(0, maximumBytes)
	}

	public func append(sequence: Int, body: DAPOutputEventBody) {
		let byteCount = body.output.lengthOfBytes(using: .utf8)
		guard byteCount <= maximumBytes else {
			return
		}
		while storedBytes + byteCount > maximumBytes, let first = entries.first {
			storedBytes -= first.body.output.lengthOfBytes(using: .utf8)
			entries.removeFirst()
		}
		guard storedBytes + byteCount <= maximumBytes else {
			return
		}
		entries.append(DebugOutputRecoveryEntry(sequence: sequence, body: body))
		storedBytes += byteCount
	}

	public func snapshot() -> [DebugOutputRecoveryEntry] {
		entries
	}
}
