// @file LSP hover markup rendering and tooltip presentation.
import AppKit
import ItsyLSP

enum LSPHoverMarkupRenderer {
	private static let baseFont = NSFont.systemFont(ofSize: 12)
	private static let headingFont = NSFont.systemFont(ofSize: 14, weight: .semibold)
	private static let monoFont = NSFont.monospacedSystemFont(ofSize: 12, weight: .regular)

	static func attributedString(for hover: LSPHover) -> NSAttributedString {
		let output = NSMutableAttributedString()
		for (index, block) in blocks(for: hover.contents).enumerated() {
			if index > 0 {
				output.append(NSAttributedString(string: "\n\n", attributes: baseAttributes()))
			}
			appendMarkdown(block.value, kind: block.kind, to: output)
		}
		return output
	}

	private static func blocks(for contents: LSPHoverContents) -> [(kind: LSPMarkupKind, value: String)] {
		switch contents {
		case let .markup(content):
			return [(content.kind, content.value)]
		case let .markedStrings(items):
			return items.map { item in
				switch item {
				case let .string(value):
					return (.markdown, value)
				case let .languageString(language, value):
					return (.markdown, "```\(language)\n\(value)\n```")
				}
			}
		}
	}

	private static func appendMarkdown(_ text: String, kind: LSPMarkupKind, to output: NSMutableAttributedString) {
		if kind == .plaintext {
			output.append(NSAttributedString(string: text, attributes: baseAttributes()))
			return
		}
		var inCodeBlock = false
		var codeLines: [String] = []
		for rawLine in text.components(separatedBy: .newlines) {
			let line = rawLine.trimmingCharacters(in: .whitespaces)
			if line.hasPrefix("```") {
				if inCodeBlock {
					appendCodeBlock(codeLines.joined(separator: "\n"), to: output)
					codeLines = []
				}
				inCodeBlock.toggle()
				continue
			}
			if inCodeBlock {
				codeLines.append(rawLine)
				continue
			}
			appendMarkdownLine(rawLine, to: output)
		}
		if !codeLines.isEmpty {
			appendCodeBlock(codeLines.joined(separator: "\n"), to: output)
		}
	}

	private static func appendMarkdownLine(_ line: String, to output: NSMutableAttributedString) {
		let trimmed = line.trimmingCharacters(in: .whitespaces)
		if trimmed.isEmpty {
			if output.length > 0 {
				output.append(NSAttributedString(string: "\n", attributes: baseAttributes()))
			}
			return
		}
		if trimmed.hasPrefix("#") {
			let title = trimmed.drop(while: { $0 == "#" || $0 == " " })
			output.append(NSAttributedString(string: String(title), attributes: headingAttributes()))
		} else {
			appendInline(line, to: output)
		}
		output.append(NSAttributedString(string: "\n", attributes: baseAttributes()))
	}

	private static func appendInline(_ text: String, to output: NSMutableAttributedString) {
		var index = text.startIndex
		while index < text.endIndex {
			if text[index] == "`", let end = text[text.index(after: index)...].firstIndex(of: "`") {
				let value = String(text[text.index(after: index) ..< end])
				output.append(NSAttributedString(string: value, attributes: codeAttributes()))
				index = text.index(after: end)
				continue
			}
			if text[index] == "[", let parsed = parseLink(in: text, from: index) {
				var attrs = baseAttributes()
				attrs[.link] = parsed.url
				attrs[.foregroundColor] = NSColor.linkColor
				output.append(NSAttributedString(string: parsed.title, attributes: attrs))
				index = parsed.end
				continue
			}
			output.append(NSAttributedString(string: String(text[index]), attributes: baseAttributes()))
			index = text.index(after: index)
		}
	}

	private static func parseLink(in text: String, from start: String.Index) -> (title: String, url: URL, end: String.Index)? {
		guard
			let closeTitle = text[text.index(after: start)...].firstIndex(of: "]"),
			closeTitle < text.index(before: text.endIndex),
			text[text.index(after: closeTitle)] == "(",
			let closeURL = text[text.index(after: closeTitle)...].firstIndex(of: ")")
		else {
			return nil
		}
		let urlStart = text.index(closeTitle, offsetBy: 2)
		guard let url = URL(string: String(text[urlStart ..< closeURL])) else {
			return nil
		}
		return (String(text[text.index(after: start) ..< closeTitle]), url, text.index(after: closeURL))
	}

	private static func appendCodeBlock(_ text: String, to output: NSMutableAttributedString) {
		let value = text.hasSuffix("\n") ? text : "\(text)\n"
		output.append(NSAttributedString(string: value, attributes: codeAttributes()))
	}

	private static func baseAttributes() -> [NSAttributedString.Key: Any] {
		[
			.font: baseFont,
			.foregroundColor: NSColor.labelColor,
		]
	}

	private static func headingAttributes() -> [NSAttributedString.Key: Any] {
		[
			.font: headingFont,
			.foregroundColor: NSColor.labelColor,
		]
	}

	private static func codeAttributes() -> [NSAttributedString.Key: Any] {
		[
			.font: monoFont,
			.foregroundColor: NSColor.labelColor,
			.backgroundColor: NSColor.textBackgroundColor,
		]
	}
}

final class HoverTooltipViewController: NSViewController {
	private let hover: LSPHover

	init(hover: LSPHover) {
		self.hover = hover
		super.init(nibName: nil, bundle: nil)
	}

	required init?(coder _: NSCoder) {
		nil
	}

	override func loadView() {
		let attributed = LSPHoverMarkupRenderer.attributedString(for: hover)
		let width: CGFloat = 420
		let bounds = attributed.boundingRect(
			with: NSSize(width: width - 24, height: .greatestFiniteMagnitude),
			options: [.usesLineFragmentOrigin, .usesFontLeading]
		)
		let height = min(320, max(72, ceil(bounds.height) + 24))
		let container = NSView(frame: NSRect(x: 0, y: 0, width: width, height: height))
		let scrollView = NSScrollView(frame: container.bounds)
		scrollView.autoresizingMask = [.width, .height]
		scrollView.hasVerticalScroller = true
		scrollView.drawsBackground = false
		scrollView.borderType = .noBorder
		let textView = NSTextView(frame: NSRect(x: 0, y: 0, width: width, height: height))
		textView.isEditable = false
		textView.isSelectable = true
		textView.drawsBackground = false
		textView.textContainerInset = NSSize(width: 10, height: 8)
		textView.textStorage?.setAttributedString(attributed)
		scrollView.documentView = textView
		container.addSubview(scrollView)
		preferredContentSize = container.frame.size
		view = container
	}
}
