import AppKit
import ItsyRender

final class CompositeGutterDecorator: GutterDecorator {
	private let decorators: [GutterDecorator]

	init(decorators: [GutterDecorator]) {
		self.decorators = decorators
	}

	func gutterMarkers(in lineRange: Range<Int>, for view: MetalTextView) -> [GutterMarker] {
		decorators.enumerated().flatMap { decoratorIndex, decorator in
			decorator.gutterMarkers(in: lineRange, for: view).map { marker in
				GutterMarker(
					id: "\(decoratorIndex):\(marker.id)",
					line: marker.line,
					severity: marker.severity,
					message: marker.message,
					color: marker.color,
					placement: marker.placement,
					shape: marker.shape
				)
			}
		}
	}

	func gutterMarkerClicked(_ marker: GutterMarker, in view: MetalTextView) {
		guard let routed = routedMarker(from: marker) else {
			return
		}
		decorators[routed.index].gutterMarkerClicked(routed.marker, in: view)
	}

	func gutterMarkerRightClicked(_ marker: GutterMarker, in view: MetalTextView, event: NSEvent) -> Bool {
		guard let routed = routedMarker(from: marker) else {
			return false
		}
		return decorators[routed.index].gutterMarkerRightClicked(routed.marker, in: view, event: event)
	}

	func gutterLineClicked(_ line: Int, in view: MetalTextView) -> Bool {
		for decorator in decorators where decorator.gutterLineClicked(line, in: view) {
			return true
		}
		return false
	}

	func gutterLineRightClicked(_ line: Int, in view: MetalTextView, event: NSEvent) -> Bool {
		for decorator in decorators where decorator.gutterLineRightClicked(line, in: view, event: event) {
			return true
		}
		return false
	}

	func gutterPopoverViewController(for marker: GutterMarker, in view: MetalTextView) -> NSViewController? {
		guard let routed = routedMarker(from: marker) else {
			return nil
		}
		return decorators[routed.index].gutterPopoverViewController(for: routed.marker, in: view)
	}

	private func routedMarker(from marker: GutterMarker) -> (index: Int, marker: GutterMarker)? {
		let parts = marker.id.split(separator: ":", maxSplits: 1).map(String.init)
		guard parts.count == 2, let index = Int(parts[0]), decorators.indices.contains(index) else {
			return nil
		}
		var routed = marker
		routed.id = parts[1]
		return (index, routed)
	}
}
