import Darwin
import Foundation
import ItsyEditor
import Testing

@Test func atomicFileWriterReplacesDestinationWithoutTruncation() throws {
	let directory = try temporaryDirectory()
	defer { try? FileManager.default.removeItem(at: directory) }
	let destination = directory.appendingPathComponent("document.txt")
	try Data("before".utf8).write(to: destination)

	try AtomicFileWriter.write(data: Data("after".utf8), to: destination)
	#expect(try Data(contentsOf: destination) == Data("after".utf8))
}

@Test func atomicFileWriterFailureLeavesDestinationUnchanged() throws {
	let directory = try temporaryDirectory()
	defer { try? FileManager.default.removeItem(at: directory) }
	let destination = directory.appendingPathComponent("document.txt")
	let original = Data("before".utf8)
	try original.write(to: destination)
	var operations = AtomicFileOperations.live
	operations.write = { _, _, _ in
		errno = ENOSPC
		return -1
	}

	#expect(throws: AtomicFileWriteError.self) {
		try AtomicFileWriter.write(data: Data("after".utf8), to: destination, operations: operations)
	}
	#expect(try Data(contentsOf: destination) == original)
}

@Test func atomicFileWriterMapsPermissionAndDiskErrorsToCocoaErrors() {
	let diskFull = AtomicFileWriteError.writeFailed(path: "/tmp/file", code: ENOSPC).cocoaError
	let permission = AtomicFileWriteError.openTemporary(path: "/tmp/file", code: EACCES).cocoaError
	#expect(diskFull.code == .fileWriteOutOfSpace)
	#expect(permission.code == .fileWriteNoPermission)
	#expect(diskFull.localizedDescription.contains("No space"))
	#expect(permission.localizedDescription.contains("Permission denied"))
}

private func temporaryDirectory() throws -> URL {
	let directory = FileManager.default.temporaryDirectory.appendingPathComponent("itsy-atomic-\(UUID().uuidString)", isDirectory: true)
	try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
	return directory
}
