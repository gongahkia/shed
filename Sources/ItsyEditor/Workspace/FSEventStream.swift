import CoreServices
import Foundation

public struct WorkspaceFileEvent: Equatable, Sendable {
	public var url: URL
	public var flags: FSEventStreamEventFlags
	public var eventID: FSEventStreamEventId

	public init(url: URL, flags: FSEventStreamEventFlags, eventID: FSEventStreamEventId) {
		self.url = url
		self.flags = flags
		self.eventID = eventID
	}

	public var requiresFullRescan: Bool {
		let rescanFlags = [
			kFSEventStreamEventFlagMustScanSubDirs,
			kFSEventStreamEventFlagUserDropped,
			kFSEventStreamEventFlagKernelDropped,
			kFSEventStreamEventFlagEventIdsWrapped,
			kFSEventStreamEventFlagRootChanged,
		]
		return rescanFlags.contains { flags & FSEventStreamEventFlags($0) != 0 }
	}
}

public struct WorkspaceFileEventBatch: Equatable, Sendable {
	public var events: [WorkspaceFileEvent]
	public var requiresFullRescan: Bool
	public var lastEventID: FSEventStreamEventId?

	public init(events: [WorkspaceFileEvent]) {
		self.events = Self.coalesced(events)
		requiresFullRescan = events.contains { $0.requiresFullRescan }
		lastEventID = events.map(\.eventID).max()
	}

	private static func coalesced(_ events: [WorkspaceFileEvent]) -> [WorkspaceFileEvent] {
		var positions: [String: Int] = [:]
		var result: [WorkspaceFileEvent] = []
		for event in events {
			let key = event.url.standardizedFileURL.path
			if let position = positions[key] {
				result[position] = event
			} else {
				positions[key] = result.count
				result.append(event)
			}
		}
		return result
	}
}

public struct WorkspaceFSEventIDStore {
	public var directory: URL
	public var fileManager: FileManager

	public init(directory: URL = Self.defaultDirectory(), fileManager: FileManager = .default) {
		self.directory = directory
		self.fileManager = fileManager
	}

	public static func defaultDirectory() -> URL {
		let home = FileManager.default.homeDirectoryForCurrentUser
		return home
			.appendingPathComponent(".config", isDirectory: true)
			.appendingPathComponent("itsy", isDirectory: true)
			.appendingPathComponent("fsevents", isDirectory: true)
	}

	public func eventID(for workspace: URL) -> FSEventStreamEventId? {
		let url = storageURL(for: workspace)
		guard let text = try? String(contentsOf: url, encoding: .utf8),
		      let value = UInt64(text.trimmingCharacters(in: .whitespacesAndNewlines))
		else {
			return nil
		}
		return FSEventStreamEventId(value)
	}

	public func save(eventID: FSEventStreamEventId, for workspace: URL) {
		do {
			try fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
			try "\(UInt64(eventID))\n".write(to: storageURL(for: workspace), atomically: true, encoding: .utf8)
		} catch {
			return
		}
	}

	func storageURL(for workspace: URL) -> URL {
		directory.appendingPathComponent(Self.workspaceKey(for: workspace) + ".id")
	}

	static func workspaceKey(for workspace: URL) -> String {
		let path = workspace.standardizedFileURL.path
		var hash: UInt64 = 0xcbf29ce484222325
		for byte in path.utf8 {
			hash ^= UInt64(byte)
			hash &*= 0x100000001b3
		}
		return String(hash, radix: 16)
	}
}

public final class WorkspaceFSEventStream: @unchecked Sendable {
	public typealias Handler = @Sendable (WorkspaceFileEventBatch) -> Void

	private let root: URL
	private let queue: DispatchQueue
	private let store: WorkspaceFSEventIDStore
	private let latency: TimeInterval
	private let debounce: TimeInterval
	private let handler: Handler
	private var stream: FSEventStreamRef?
	private var pendingEvents: [WorkspaceFileEvent] = []
	private var scheduledFlush = false

	public init(
		root: URL,
		queue: DispatchQueue = DispatchQueue(label: "dev.itsy.workspace-fsevents"),
		store: WorkspaceFSEventIDStore = WorkspaceFSEventIDStore(),
		latency: TimeInterval = 0.2,
		debounce: TimeInterval = 0.12,
		handler: @escaping Handler
	) {
		self.root = root
		self.queue = queue
		self.store = store
		self.latency = latency
		self.debounce = debounce
		self.handler = handler
	}

	deinit {
		stop()
	}

	@discardableResult
	public func start() -> Bool {
		stop()
		let pointer = Unmanaged.passUnretained(self).toOpaque()
		var context = FSEventStreamContext(version: 0, info: pointer, retain: nil, release: nil, copyDescription: nil)
		let since = store.eventID(for: root) ?? FSEventStreamEventId(kFSEventStreamEventIdSinceNow)
		guard let created = FSEventStreamCreate(
			kCFAllocatorDefault,
			Self.callback,
			&context,
			[root.path] as CFArray,
			since,
			latency,
			Self.flags
		) else {
			return false
		}
		FSEventStreamSetDispatchQueue(created, queue)
		guard FSEventStreamStart(created) else {
			FSEventStreamInvalidate(created)
			FSEventStreamRelease(created)
			return false
		}
		stream = created
		return true
	}

	public func stop() {
		guard let stream else {
			return
		}
		FSEventStreamStop(stream)
		FSEventStreamInvalidate(stream)
		FSEventStreamRelease(stream)
		self.stream = nil
	}

	private func append(events: [WorkspaceFileEvent]) {
		pendingEvents.append(contentsOf: events)
		guard !scheduledFlush else {
			return
		}
		scheduledFlush = true
		queue.asyncAfter(deadline: .now() + debounce) { [weak self] in
			self?.flush()
		}
	}

	private func flush() {
		let batch = WorkspaceFileEventBatch(events: pendingEvents)
		pendingEvents.removeAll(keepingCapacity: true)
		scheduledFlush = false
		if let lastEventID = batch.lastEventID {
			store.save(eventID: lastEventID, for: root)
		}
		if !batch.events.isEmpty {
			handler(batch)
		}
	}

	private static let flags = UInt32(
		kFSEventStreamCreateFlagFileEvents |
			kFSEventStreamCreateFlagNoDefer |
			kFSEventStreamCreateFlagUseCFTypes |
			kFSEventStreamCreateFlagWatchRoot
	)

	private static let callback: FSEventStreamCallback = { _, context, count, paths, flags, ids in
		guard let context else {
			return
		}
		let stream = Unmanaged<WorkspaceFSEventStream>.fromOpaque(context).takeUnretainedValue()
		let cfPaths = unsafeBitCast(paths, to: CFArray.self)
		var events: [WorkspaceFileEvent] = []
		events.reserveCapacity(count)
		for index in 0 ..< count {
			let pointer = CFArrayGetValueAtIndex(cfPaths, index)
			let cfString = unsafeBitCast(pointer, to: CFString.self)
			events.append(WorkspaceFileEvent(
				url: URL(fileURLWithPath: cfString as String),
				flags: flags[index],
				eventID: ids[index]
			))
		}
		stream.append(events: events)
	}
}
