import Darwin
import Dispatch

public enum LineFeedIndexer {
	private static let strideBytes = 64 * 1024

	public static func lineStarts(in bytes: UnsafeBufferPointer<UInt8>) -> [Int] {
		guard bytes.count > 0 else {
			return [0]
		}
		guard let baseAddress = bytes.baseAddress else {
			return fallbackLineStarts(in: bytes)
		}
		var starts = [0]
		starts.reserveCapacity(estimatedLineCount(in: bytes.count))
		var chunkStart = 0
		while chunkStart < bytes.count {
			let chunkEnd = min(bytes.count, chunkStart + strideBytes)
			var cursor = chunkStart
			while cursor < chunkEnd {
				let remaining = chunkEnd - cursor
				let raw = UnsafeRawPointer(baseAddress.advanced(by: cursor))
				guard let match = memchr(raw, 10, remaining) else {
					break
				}
				let pointer = match.assumingMemoryBound(to: UInt8.self)
				let offset = baseAddress.distance(to: pointer)
				starts.append(offset + 1)
				cursor = offset + 1
			}
			chunkStart = chunkEnd
		}
		return starts
	}

	static func fallbackLineStarts(in bytes: UnsafeBufferPointer<UInt8>) -> [Int] {
		var starts = [0]
		starts.reserveCapacity(estimatedLineCount(in: bytes.count))
		var index = 0
		while index + 4 <= bytes.count {
			if bytes[index] == 10 {
				starts.append(index + 1)
			}
			if bytes[index + 1] == 10 {
				starts.append(index + 2)
			}
			if bytes[index + 2] == 10 {
				starts.append(index + 3)
			}
			if bytes[index + 3] == 10 {
				starts.append(index + 4)
			}
			index += 4
		}
		while index < bytes.count {
			if bytes[index] == 10 {
				starts.append(index + 1)
			}
			index += 1
		}
		return starts
	}

	private static func estimatedLineCount(in byteCount: Int) -> Int {
		max(1, byteCount / 80)
	}
}
