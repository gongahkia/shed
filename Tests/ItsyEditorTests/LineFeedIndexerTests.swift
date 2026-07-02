@testable import ItsyEditor
import Dispatch
import Testing

@Test func lineFeedIndexerReturnsLineStartOffsets() {
	let bytes = Array("alpha\nbeta\n\ngamma".utf8)
	let starts = bytes.withUnsafeBufferPointer {
		LineFeedIndexer.lineStarts(in: $0)
	}
	#expect(starts == [0, 6, 11, 12])
}

@Test func lineFeedIndexerMatchesFallbackAcrossChunkBoundary() {
	var bytes = [UInt8](repeating: 65, count: 64 * 1024 + 16)
	bytes[64 * 1024 - 1] = 10
	bytes[64 * 1024] = 10
	bytes[bytes.count - 1] = 10
	let indexed = bytes.withUnsafeBufferPointer {
		LineFeedIndexer.lineStarts(in: $0)
	}
	let fallback = bytes.withUnsafeBufferPointer {
		LineFeedIndexer.fallbackLineStarts(in: $0)
	}
	#expect(indexed == fallback)
	#expect(indexed == [0, 64 * 1024, 64 * 1024 + 1, bytes.count])
}

@Test func lineFeedIndexerCountsWithoutMaterializingStarts() {
	var bytes = [UInt8](repeating: 65, count: 128 * 1024 + 16)
	bytes[4095] = 10
	bytes[64 * 1024] = 10
	bytes[bytes.count - 1] = 10
	var didIndexPrefix = false
	let count = bytes.withUnsafeBufferPointer {
		LineFeedIndexer.lineFeedCount(in: $0, notifyAfterPrefix: 4 * 1024) {
			didIndexPrefix = true
		}
	}
	#expect(count == 3)
	#expect(didIndexPrefix)
}

@Test func lineFeedIndexerIndexesHundredKLinesUnderBudget() {
	let line = Array("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef\n".utf8)
	let bytes = Array(repeating: line, count: 100_000).flatMap { $0 }
	let start = DispatchTime.now().uptimeNanoseconds
	let starts = bytes.withUnsafeBufferPointer {
		LineFeedIndexer.lineStarts(in: $0)
	}
	let elapsedMS = Double(DispatchTime.now().uptimeNanoseconds - start) / 1_000_000
	#expect(starts.count == 100_001)
	#expect(starts.first == 0)
	#expect(starts.last == bytes.count)
	#expect(elapsedMS < 50)
}
