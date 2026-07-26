import Darwin
import Foundation

public enum MMapBufferError: Error, Equatable, CustomStringConvertible {
	case openFailed(path: String, code: Int32)
	case statFailed(path: String, code: Int32)
	case notRegularFile(path: String)
	case fileTooLarge(path: String, size: Int64)
	case mapFailed(path: String, code: Int32)

	public var description: String {
		switch self {
		case let .openFailed(path, code):
			"open failed for \(path): \(Self.message(code))"
		case let .statFailed(path, code):
			"fstat failed for \(path): \(Self.message(code))"
		case let .notRegularFile(path):
			"not a regular file: \(path)"
		case let .fileTooLarge(path, size):
			"file too large to map: \(path) (\(size) bytes)"
		case let .mapFailed(path, code):
			"mmap failed for \(path): \(Self.message(code))"
		}
	}

	private static func message(_ code: Int32) -> String {
		String(cString: strerror(code))
	}
}

public final class MMapBuffer: @unchecked Sendable {
	private let baseAddress: UnsafeMutableRawPointer?
	public let count: Int

	public init(url: URL) throws {
		let path = url.path
		let descriptor = open(path, O_RDONLY)
		guard descriptor >= 0 else {
			throw MMapBufferError.openFailed(path: path, code: errno)
		}
		defer {
			close(descriptor)
		}

		var info = stat()
		guard fstat(descriptor, &info) == 0 else {
			throw MMapBufferError.statFailed(path: path, code: errno)
		}
		guard (info.st_mode & S_IFMT) == S_IFREG else {
			throw MMapBufferError.notRegularFile(path: path)
		}
		guard info.st_size >= 0, UInt64(info.st_size) <= UInt64(Int.max) else {
			throw MMapBufferError.fileTooLarge(path: path, size: Int64(info.st_size))
		}

		count = Int(info.st_size)
		if count == 0 {
			baseAddress = nil
			return
		}

		let mapped = mmap(nil, count, PROT_READ, MAP_PRIVATE, descriptor, 0)
		guard mapped != MAP_FAILED else {
			throw MMapBufferError.mapFailed(path: path, code: errno)
		}
		baseAddress = mapped
	}

	deinit {
		if let baseAddress, count > 0 {
			munmap(baseAddress, count)
		}
	}

	public var bytes: UnsafeBufferPointer<UInt8> {
		guard let baseAddress, count > 0 else {
			return UnsafeBufferPointer(start: nil, count: 0)
		}
		return UnsafeBufferPointer(start: baseAddress.assumingMemoryBound(to: UInt8.self), count: count)
	}

	public func slice(_ range: Range<Int>) -> Data {
		precondition(range.lowerBound >= 0 && range.upperBound <= count, "mmap slice range out of bounds")
		guard !range.isEmpty, let baseAddress else {
			return Data()
		}
		let pointer = baseAddress.advanced(by: range.lowerBound)
		return Data(bytesNoCopy: pointer, count: range.count, deallocator: .none)
	}
}
