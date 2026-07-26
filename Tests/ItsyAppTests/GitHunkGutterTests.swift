@testable import ItsyApp
import ItsyConfig
import ItsyEditor
import ItsyRender
import Testing

@Test @MainActor func gitHunkGutterUsesThemeColors() {
	let decorator = GitHunkGutterDecorator(
		indicators: [
			GitHunkIndicator(line: 0, kind: .added),
			GitHunkIndicator(line: 1, kind: .modified),
			GitHunkIndicator(line: 2, kind: .deleted),
		],
		mode: .head,
		theme: .init(added: "#010203", modified: "#040506", removed: "#070809")
	)
	let view = MetalTextView(frame: .zero)
	let markers = decorator.gutterMarkers(in: 0 ..< 3, for: view)

	#expect(markers.map(\.color) == [
		SIMD4<Float>(Float(1) / 255, Float(2) / 255, Float(3) / 255, 1),
		SIMD4<Float>(Float(4) / 255, Float(5) / 255, Float(6) / 255, 1),
		SIMD4<Float>(Float(7) / 255, Float(8) / 255, Float(9) / 255, 1),
	])
}
