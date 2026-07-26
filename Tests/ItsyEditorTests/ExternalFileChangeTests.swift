import ItsyEditor
import Testing

@Test func externalFileChangeResolverSeparatesCleanReloadDirtyConflictAndDeletion() {
	#expect(ExternalFileChangeResolver.state(localText: "same", diskText: "same", isDirty: false, fileExists: true) == .unchanged)
	#expect(ExternalFileChangeResolver.state(localText: "local", diskText: "disk", isDirty: false, fileExists: true) == .cleanReload)
	#expect(ExternalFileChangeResolver.state(localText: "local", diskText: "disk", isDirty: true, fileExists: true) == .dirtyConflict)
	#expect(ExternalFileChangeResolver.state(localText: "local", diskText: nil, isDirty: false, fileExists: false) == .deletedClean)
	#expect(ExternalFileChangeResolver.state(localText: "local", diskText: nil, isDirty: true, fileExists: false) == .deletedDirty)
	#expect(ExternalFileChangeResolver.state(localText: "local", diskText: nil, isDirty: true, fileExists: true) == .unreadable)
}

@Test func externalFileChangeResolverHandlesAtomicReplacement() {
	#expect(ExternalFileChangeResolver.state(localText: "before", diskText: "after", isDirty: false, fileExists: true) == .cleanReload)
	#expect(ExternalFileChangeResolver.state(localText: "local", diskText: "after", isDirty: true, fileExists: true) == .dirtyConflict)
}
