import AppKit

struct ItsyTab: Equatable {
	let id: ObjectIdentifier
	let title: String
	let isDirty: Bool
	let isSelected: Bool
}

enum ItsyTabCoordinator {
	private static weak var documentController: ItsyDocumentController?
	private static let controllers = NSHashTable<EditorWindowController>.weakObjects()

	static func install(documentController: ItsyDocumentController) {
		self.documentController = documentController
		refresh()
	}

	static func register(_ controller: EditorWindowController) {
		controllers.add(controller)
		refresh()
	}

	static func refresh() {
		let documents = itsyDocuments()
		let selectedID = selectedDocument().map(ObjectIdentifier.init)
		let tabs = documents.map { document in
			ItsyTab(
				id: ObjectIdentifier(document),
				title: title(for: document),
				isDirty: document.isDocumentEdited,
				isSelected: ObjectIdentifier(document) == selectedID
			)
		}
		for controller in controllers.allObjects {
			controller.setTabs(tabs)
		}
	}

	static func selectAdjacentDocument(delta: Int) {
		let documents = itsyDocuments()
		guard !documents.isEmpty, let selected = selectedDocument(), let index = documents.firstIndex(where: { $0 === selected }) else {
			return
		}
		let next = (index + delta + documents.count) % documents.count
		selectDocument(ObjectIdentifier(documents[next]))
	}

	static func selectDocument(_ id: ObjectIdentifier) {
		guard let document = itsyDocuments().first(where: { ObjectIdentifier($0) == id }) else {
			return
		}
		documentController?.showDocument(document)
	}

	static func closeDocument(_ id: ObjectIdentifier) {
		guard let document = itsyDocuments().first(where: { ObjectIdentifier($0) == id }) else {
			return
		}
		document.close()
		refresh()
	}

	private static func itsyDocuments() -> [ItsyDocument] {
		(documentController?.documents ?? []).compactMap { $0 as? ItsyDocument }
	}

	private static func selectedDocument() -> ItsyDocument? {
		NSApplication.shared.keyWindow?.windowController?.document as? ItsyDocument
	}

	private static func title(for document: ItsyDocument) -> String {
		if let fileName = document.fileURL?.lastPathComponent {
			return fileName
		}
		return document.displayName
	}
}
