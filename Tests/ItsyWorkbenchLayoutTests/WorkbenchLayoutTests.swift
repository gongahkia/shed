import ItsyWorkbenchDSL
import ItsyWorkbenchLayout
import Testing

@Test func workbenchSolverCollapsesGitProgressively() {
	let base = WorkbenchLayoutInput(
		width: 1_900,
		height: 900,
		sidebarRequested: true,
		terminalVisible: true,
		gitVisible: true,
		preferredSidebarWidth: 240
	)
	#expect(WorkbenchLayoutSolver.resolve(base).gitMode == .full)
	var compact = base
	compact.width = 1_200
	#expect(WorkbenchLayoutSolver.resolve(compact).gitMode == .compact)
	var files = base
	files.width = 950
	#expect(WorkbenchLayoutSolver.resolve(files).gitMode == .files)
}

@Test func focusAndReviewProfilesControlCoreSurfaces() {
	var focus = WorkbenchLayoutInput(
		width: 1_400,
		height: 900,
		configuration: WorkbenchProfileBuilder.focus(),
		sidebarRequested: true,
		terminalVisible: false,
		gitVisible: false,
		preferredSidebarWidth: 240
	)
	#expect(!WorkbenchLayoutSolver.resolve(focus).showsFileTree)
	focus.configuration = WorkbenchProfileBuilder.review()
	#expect(WorkbenchLayoutSolver.resolve(focus).showsGit)
	#expect(!WorkbenchLayoutSolver.resolve(focus).showsFileTree)
}

@Test func invalidProfileOverridesAreReported() {
	#expect(WorkbenchConfigurationValidator.validate(.init(profile: .focus, fileTree: .visible)) != nil)
	#expect(WorkbenchConfigurationValidator.validate(.init(profile: .workbench, fileTree: .visible)) == nil)
}

@Test func namedProfilesExposeComposableComponentTrees() {
	let review = WorkbenchProfileBuilder.definition(for: .review)
	#expect(review.configuration.git == .visible)
	#expect(review.root == .split(axis: .horizontal, children: [
		.split(axis: .vertical, children: [.component(.tabBar), .component(.editor), .component(.terminal), .component(.statusBar)]),
		.component(.git),
	]))
}

@Test func firstPartyComponentRegistryDeclaresCanonicalMetadata() {
	let registry = WorkbenchComponents.firstParty
	#expect(registry.descriptors.map(\.id) == WorkbenchComponentID.allCases)
	#expect(registry.descriptor(for: .terminal) == .init(
		id: .terminal,
		displayName: "Terminal",
		placement: .bottomPanel,
		defaultLifecycle: .hidden,
		minimumWidth: 320,
		minimumHeight: 140
	))
	#expect(WorkbenchComponents.registry == registry.descriptorsByID)
}

@Test func workbenchComponentRegistryRejectsDuplicateComponents() {
	let descriptor = WorkbenchComponentDescriptor(id: .editor, minimumWidth: 480)
	var error: WorkbenchComponentRegistryError?
	do {
		_ = try WorkbenchComponentRegistry(descriptors: [descriptor, descriptor])
	} catch let caught as WorkbenchComponentRegistryError {
		error = caught
	} catch {}
	#expect(error == .duplicateComponent(.editor))
}
