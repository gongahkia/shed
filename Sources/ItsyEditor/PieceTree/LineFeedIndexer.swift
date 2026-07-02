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

	public static func lineFeedCount(in bytes: UnsafeBufferPointer<UInt8>) -> Int {
		scanLineFeeds(in: bytes, notifyAfterPrefix: nil, onIndexedPrefix: nil)
	}

	public static func lineFeedCount(
		in bytes: UnsafeBufferPointer<UInt8>,
		notifyAfterPrefix prefixByteCount: Int,
		onIndexedPrefix: @escaping () -> Void
	) -> Int {
		scanLineFeeds(in: bytes, notifyAfterPrefix: prefixByteCount, onIndexedPrefix: onIndexedPrefix)
	}

	private static func scanLineFeeds(
		in bytes: UnsafeBufferPointer<UInt8>,
		notifyAfterPrefix prefixByteCount: Int?,
		onIndexedPrefix: (() -> Void)?
	) -> Int {
		guard bytes.count > 0 else {
			onIndexedPrefix?()
			return 0
		}
		guard let baseAddress = bytes.baseAddress else {
			let count = fallbackLineFeedCount(in: bytes) { _ in }
			onIndexedPrefix?()
			return count
		}
		let prefixLimit = prefixByteCount.map { min(max($0, 0), bytes.count) }
		var didNotify = false
		func notifyIfNeeded(indexedThrough offset: Int) {
			guard let prefixLimit, !didNotify, offset >= prefixLimit else {
				return
			}
			didNotify = true
			onIndexedPrefix?()
		}
		var count = 0
		notifyIfNeeded(indexedThrough: 0)
		var chunkStart = 0
		while chunkStart < bytes.count {
			var chunkEnd = min(bytes.count, chunkStart + strideBytes)
			if let prefixLimit, !didNotify, chunkStart < prefixLimit, prefixLimit < chunkEnd {
				chunkEnd = prefixLimit
			}
			var cursor = chunkStart
			while cursor < chunkEnd {
				let remaining = chunkEnd - cursor
				let raw = UnsafeRawPointer(baseAddress.advanced(by: cursor))
				guard let match = memchr(raw, 10, remaining) else {
					break
				}
				let pointer = match.assumingMemoryBound(to: UInt8.self)
				let offset = baseAddress.distance(to: pointer)
				count += 1
				cursor = offset + 1
			}
			notifyIfNeeded(indexedThrough: chunkEnd)
			chunkStart = chunkEnd
		}
		return count
	}

	static func fallbackLineStarts(in bytes: UnsafeBufferPointer<UInt8>) -> [Int] {
		var starts = [0]
		starts.reserveCapacity(estimatedLineCount(in: bytes.count))
		_ = fallbackLineFeedCount(in: bytes) { offset in
			starts.append(offset + 1)
		}
		return starts
	}

	private static func fallbackLineFeedCount(in bytes: UnsafeBufferPointer<UInt8>, _ body: (Int) -> Void) -> Int {
		var count = 0
		var index = 0
		while index + 4 <= bytes.count {
			if bytes[index] == 10 {
				count += 1
				body(index)
			}
			if bytes[index + 1] == 10 {
				count += 1
				body(index + 1)
			}
			if bytes[index + 2] == 10 {
				count += 1
				body(index + 2)
			}
			if bytes[index + 3] == 10 {
				count += 1
				body(index + 3)
			}
			index += 4
		}
		while index < bytes.count {
			if bytes[index] == 10 {
				count += 1
				body(index)
			}
			index += 1
		}
		return count
	}

	private static func estimatedLineCount(in byteCount: Int) -> Int {
		max(1, byteCount / 80)
	}
}
