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
