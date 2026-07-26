@testable import ItsyEditor
import Darwin
import Foundation
import Testing

@Test func mmapBufferMapsSparseFileEdges() throws {
	let fileManager = FileManager.default
	let directory = fileManager.temporaryDirectory.appendingPathComponent("itsy-mmap-\(UUID().uuidString)", isDirectory: true)
	try fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
	defer {
		try? fileManager.removeItem(at: directory)
	}

	let url = directory.appendingPathComponent("sparse.bin")
	let descriptor = open(url.path, O_CREAT | O_RDWR | O_TRUNC, S_IRUSR | S_IWUSR)
	let fd = try #require(descriptor >= 0 ? descriptor : nil)
	defer {
		close(fd)
	}

	let size = 1_073_741_824
	#expect(ftruncate(fd, off_t(size)) == 0)
	let first = [UInt8](repeating: 0x41, count: 4096)
	let last = [UInt8](repeating: 0x5A, count: 4096)
	let firstWrite = first.withUnsafeBytes { bytes in
		pwrite(fd, bytes.baseAddress!, bytes.count, 0)
	}
	let lastWrite = last.withUnsafeBytes { bytes in
		pwrite(fd, bytes.baseAddress!, bytes.count, off_t(size - last.count))
	}
	#expect(firstWrite == first.count)
	#expect(lastWrite == last.count)

	let buffer = try MMapBuffer(url: url)
	#expect(buffer.count == size)
	#expect(Array(buffer.bytes.prefix(4)) == [0x41, 0x41, 0x41, 0x41])
	#expect(buffer.slice(0 ..< first.count) == Data(first))
	#expect(buffer.slice(size - last.count ..< size) == Data(last))
}

@Test func mmapBufferHandlesEmptyFile() throws {
	let fileManager = FileManager.default
	let directory = fileManager.temporaryDirectory.appendingPathComponent("itsy-mmap-empty-\(UUID().uuidString)", isDirectory: true)
	try fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
	defer {
		try? fileManager.removeItem(at: directory)
	}

	let url = directory.appendingPathComponent("empty.txt")
	try Data().write(to: url)
	let buffer = try MMapBuffer(url: url)
	#expect(buffer.count == 0)
	#expect(buffer.bytes.count == 0)
	#expect(buffer.slice(0 ..< 0).isEmpty)
}
