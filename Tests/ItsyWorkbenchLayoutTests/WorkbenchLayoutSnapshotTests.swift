import ItsyWorkbenchDSL
import ItsyWorkbenchLayout
import Testing

@Test(arguments: WorkbenchProfileSnapshot.all)
private func workbenchProfileSnapshotsRemainStable(_ snapshot: WorkbenchProfileSnapshot) {
	#expect(WorkbenchProfileBuilder.definition(for: snapshot.profile) == snapshot.definition)
	#expect(WorkbenchLayoutSolver.resolve(snapshot.input) == snapshot.result)
}

@Test(arguments: WorkbenchSolverSnapshot.all)
private func workbenchSolverSnapshotsRemainStable(_ snapshot: WorkbenchSolverSnapshot) {
	#expect(WorkbenchLayoutSolver.resolve(snapshot.input) == snapshot.result)
}

private struct WorkbenchProfileSnapshot: CustomTestStringConvertible {
	let name: String
	let profile: WorkbenchProfile
	let definition: WorkbenchProfileDefinition
	let input: WorkbenchLayoutInput
	let result: WorkbenchLayoutResult

	var testDescription: String { name }

	static let all: [WorkbenchProfileSnapshot] = [
		.init(
			name: "workbench",
			profile: .workbench,
			definition: .init(
				configuration: .init(profile: .workbench),
				root: .split(axis: .horizontal, children: [
					.component(.fileTree),
					.split(axis: .vertical, children: [.component(.tabBar), .component(.editor), .component(.terminal), .component(.statusBar)]),
					.component(.git),
				])
			),
			input: .init(width: 1_600, height: 900, configuration: .init(profile: .workbench), sidebarRequested: true, terminalVisible: true, gitVisible: true, preferredSidebarWidth: 240),
			result: .init(showsFileTree: true, showsTerminal: true, showsGit: true, sidebarWidth: 240, gitWidth: 640, terminalHeight: 280, gitMode: .full)
		),
		.init(
			name: "focus",
			profile: .focus,
			definition: .init(
				configuration: .init(profile: .focus, fileTree: .hidden),
				root: .split(axis: .vertical, children: [.component(.tabBar), .component(.editor), .component(.terminal), .component(.statusBar)])
			),
			input: .init(width: 1_600, height: 900, configuration: .init(profile: .focus, fileTree: .hidden), sidebarRequested: true, terminalVisible: true, gitVisible: true, preferredSidebarWidth: 240),
			result: .init(showsFileTree: false, showsTerminal: true, showsGit: true, sidebarWidth: 0, gitWidth: 640, terminalHeight: 280, gitMode: .full)
		),
		.init(
			name: "review",
			profile: .review,
			definition: .init(
				configuration: .init(profile: .review, fileTree: .hidden, git: .visible),
				root: .split(axis: .horizontal, children: [
					.split(axis: .vertical, children: [.component(.tabBar), .component(.editor), .component(.terminal), .component(.statusBar)]),
					.component(.git),
				])
			),
			input: .init(width: 1_600, height: 900, configuration: .init(profile: .review, fileTree: .hidden, git: .visible), sidebarRequested: true, terminalVisible: true, gitVisible: false, preferredSidebarWidth: 240),
			result: .init(showsFileTree: false, showsTerminal: true, showsGit: true, sidebarWidth: 0, gitWidth: 640, terminalHeight: 280, gitMode: .full)
		),
	]
}

private struct WorkbenchSolverSnapshot: CustomTestStringConvertible {
	let name: String
	let input: WorkbenchLayoutInput
	let result: WorkbenchLayoutResult

	var testDescription: String { name }

	static let all: [WorkbenchSolverSnapshot] = [
		.init(
			name: "wide",
			input: .init(width: 1_600, height: 900, sidebarRequested: true, terminalVisible: true, gitVisible: true, preferredSidebarWidth: 240),
			result: .init(showsFileTree: true, showsTerminal: true, showsGit: true, sidebarWidth: 240, gitWidth: 640, terminalHeight: 280, gitMode: .full)
		),
		.init(
			name: "compact",
			input: .init(width: 1_200, height: 900, sidebarRequested: true, terminalVisible: true, gitVisible: true, preferredSidebarWidth: 240),
			result: .init(showsFileTree: true, showsTerminal: true, showsGit: true, sidebarWidth: 240, gitWidth: 440, terminalHeight: 280, gitMode: .compact)
		),
		.init(
			name: "files-collapsed",
			input: .init(width: 950, height: 900, sidebarRequested: true, terminalVisible: true, gitVisible: true, preferredSidebarWidth: 240),
			result: .init(showsFileTree: true, showsTerminal: true, showsGit: false, sidebarWidth: 240, gitWidth: 230, terminalHeight: 280, gitMode: .files)
		),
		.init(
			name: "sidebar-collapsed",
			input: .init(width: 639, height: 900, sidebarRequested: true, terminalVisible: true, gitVisible: true, preferredSidebarWidth: 240),
			result: .init(showsFileTree: false, showsTerminal: true, showsGit: false, sidebarWidth: 0, gitWidth: 159, terminalHeight: 280, gitMode: .files)
		),
		.init(
			name: "scaled",
			input: .init(width: 1_600, height: 900, interfaceScale: 1.5, sidebarRequested: true, terminalVisible: true, gitVisible: true, preferredSidebarWidth: 240),
			result: .init(showsFileTree: true, showsTerminal: true, showsGit: true, sidebarWidth: 360, gitWidth: 520, terminalHeight: 378, gitMode: .compact)
		),
		.init(
			name: "full-hysteresis",
			input: .init(width: 1_420, height: 900, sidebarRequested: true, terminalVisible: true, gitVisible: true, preferredSidebarWidth: 240, previousGitMode: .full),
			result: .init(showsFileTree: true, showsTerminal: true, showsGit: true, sidebarWidth: 240, gitWidth: 640, terminalHeight: 280, gitMode: .full)
		),
		.init(
			name: "compact-hysteresis",
			input: .init(width: 1_420, height: 900, sidebarRequested: true, terminalVisible: true, gitVisible: true, preferredSidebarWidth: 240, previousGitMode: .compact),
			result: .init(showsFileTree: true, showsTerminal: true, showsGit: true, sidebarWidth: 240, gitWidth: 440, terminalHeight: 280, gitMode: .compact)
		),
	]
}
