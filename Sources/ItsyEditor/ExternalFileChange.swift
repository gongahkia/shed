public enum ExternalFileChangeState: Equatable, Sendable {
	case unchanged
	case cleanReload
	case dirtyConflict
	case deletedClean
	case deletedDirty
	case unreadable
}

public enum ExternalFileChangeResolver {
	public static func state(localText: String, diskText: String?, isDirty: Bool, fileExists: Bool) -> ExternalFileChangeState {
		guard fileExists else {
			return isDirty ? .deletedDirty : .deletedClean
		}
		guard let diskText else {
			return .unreadable
		}
		guard diskText != localText else {
			return .unchanged
		}
		return isDirty ? .dirtyConflict : .cleanReload
	}
}
