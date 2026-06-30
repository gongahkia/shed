import AppKit
import ItsyLSP

final class SignatureHelpViewController: NSViewController {
	private let help: LSPSignatureHelp

	init(help: LSPSignatureHelp) {
		self.help = help
		super.init(nibName: nil, bundle: nil)
	}

	required init?(coder _: NSCoder) {
		nil
	}

	override func loadView() {
		let attributed = Self.attributedString(for: help)
		let width: CGFloat = 520
		let bounds = attributed.boundingRect(
			with: NSSize(width: width - 24, height: .greatestFiniteMagnitude),
			options: [.usesLineFragmentOrigin, .usesFontLeading]
		)
		let height = min(140, max(44, ceil(bounds.height) + 20))
		let container = NSView(frame: NSRect(x: 0, y: 0, width: width, height: height))
		let textView = NSTextView(frame: container.bounds)
		textView.autoresizingMask = [.width, .height]
		textView.isEditable = false
		textView.isSelectable = true
		textView.drawsBackground = false
		textView.textContainerInset = NSSize(width: 10, height: 8)
		textView.textStorage?.setAttributedString(attributed)
		container.addSubview(textView)
		preferredContentSize = container.frame.size
		view = container
	}

	private static func attributedString(for help: LSPSignatureHelp) -> NSAttributedString {
		guard let signature = activeSignature(in: help) else {
			return NSAttributedString(string: "")
		}
		let baseFont = NSFont.monospacedSystemFont(ofSize: 12, weight: .regular)
		let boldFont = NSFont.monospacedSystemFont(ofSize: 12, weight: .semibold)
		let output = NSMutableAttributedString(
			string: signature.label,
			attributes: [
				.font: baseFont,
				.foregroundColor: NSColor.labelColor,
			]
		)
		if let range = activeParameterRange(in: signature, help: help) {
			output.addAttributes([.font: boldFont], range: range)
		}
		return output
	}

	private static func activeSignature(in help: LSPSignatureHelp) -> LSPSignatureInformation? {
		guard !help.signatures.isEmpty else {
			return nil
		}
		let index = min(max(help.activeSignature ?? 0, 0), help.signatures.count - 1)
		return help.signatures[index]
	}

	private static func activeParameterRange(in signature: LSPSignatureInformation, help: LSPSignatureHelp) -> NSRange? {
		let activeParameter = signature.activeParameter ?? help.activeParameter
		guard
			let activeParameter,
			let parameters = signature.parameters,
			activeParameter >= 0,
			activeParameter < parameters.count
		else {
			return nil
		}
		let label = signature.label as NSString
		switch parameters[activeParameter].label {
		case let .string(value):
			let range = label.range(of: value)
			return range.location == NSNotFound ? nil : range
		case let .offsets(start, end):
			guard start >= 0, end > start, end <= label.length else {
				return nil
			}
			return NSRange(location: start, length: end - start)
		}
	}
}
