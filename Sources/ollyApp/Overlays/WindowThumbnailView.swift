import AppKit
import ollyKit

final class AltTabSwitcherView: NSView {
    private let thumbnailCache: WindowThumbnailCache
    private var thumbnailViews: [WindowThumbnailView] = []
    private var listRows: [AltTabListRowView] = []
    private(set) var itemCount = 0
    private(set) var mode = AltTabPresentationMode.grid

    init(thumbnailCache: WindowThumbnailCache) {
        self.thumbnailCache = thumbnailCache
        super.init(frame: .zero)
        wantsLayer = true
        layer?.backgroundColor = NSColor.windowBackgroundColor.withAlphaComponent(0.72).cgColor
        setAccessibilityElement(true)
        setAccessibilityRole(.list)
        setAccessibilityLabel(L10n.s("Alt-Tab windows", "alt-tab overlay accessibility label"))
    }

    required init?(coder: NSCoder) {
        nil
    }

    func configure(
        windows: [WindowState],
        selectedID: WindowID?,
        mode: AltTabPresentationMode,
        reduceMotion: Bool
    ) {
        resetContent()
        self.mode = mode
        itemCount = windows.count
        switch mode {
        case .grid:
            configureGrid(windows: Array(windows.prefix(50)), selectedID: selectedID, reduceMotion: reduceMotion)
        case .list:
            configureList(windows: windows, selectedID: selectedID)
        }
        let children: [NSView]
        switch mode {
        case .grid:
            children = thumbnailViews
        case .list:
            children = listRows
        }
        setAccessibilityChildren(children)
        setAccessibilityChildrenInNavigationOrder(children.map { $0 as any NSAccessibilityElementProtocol })
    }

    func resetContent() {
        thumbnailViews.forEach { $0.cancelThumbnailLoad() }
        subviews.forEach { $0.removeFromSuperview() }
        thumbnailViews = []
        listRows = []
        itemCount = 0
        setAccessibilityChildren([])
        setAccessibilityChildrenInNavigationOrder([any NSAccessibilityElementProtocol]())
    }

    private func configureGrid(windows: [WindowState], selectedID: WindowID?, reduceMotion: Bool) {
        let layout = AltTabGridLayout.make(itemCount: windows.count, in: bounds)
        for (index, window) in windows.enumerated() {
            let view = WindowThumbnailView()
            let frame = layout.itemFrames[index]
            view.frame = frame
            view.configure(
                window: window,
                isSelected: window.id == selectedID,
                thumbnailCache: thumbnailCache,
                thumbnailSize: CGSize(width: frame.width, height: max(1, frame.height - 34)),
                reduceMotion: reduceMotion
            )
            addSubview(view)
            thumbnailViews.append(view)
        }
    }

    private func configureList(windows: [WindowState], selectedID: WindowID?) {
        let width = min(bounds.width * 0.62, 680)
        let rowHeight: CGFloat = 42
        let visibleHeight = min(CGFloat(windows.count) * rowHeight, bounds.height * 0.72)
        let frame = CGRect(
            x: bounds.midX - width / 2,
            y: bounds.midY - visibleHeight / 2,
            width: width,
            height: visibleHeight
        )
        let scrollView = NSScrollView(frame: frame)
        scrollView.drawsBackground = false
        scrollView.hasVerticalScroller = CGFloat(windows.count) * rowHeight > visibleHeight
        let documentHeight = max(visibleHeight, CGFloat(windows.count) * rowHeight)
        let documentView = NSView(frame: CGRect(x: 0, y: 0, width: width, height: documentHeight))
        for (index, window) in windows.enumerated() {
            let row = AltTabListRowView()
            row.frame = CGRect(
                x: 0,
                y: documentHeight - CGFloat(index + 1) * rowHeight,
                width: width,
                height: rowHeight - 3
            )
            row.configure(window: window, isSelected: window.id == selectedID)
            documentView.addSubview(row)
            listRows.append(row)
        }
        scrollView.documentView = documentView
        addSubview(scrollView)
    }
}

final class WindowThumbnailView: NSView {
    private let imageView = NSImageView()
    private let titleLabel = NSTextField(labelWithString: "")
    private var task: Task<Void, Never>?

    override init(frame frameRect: NSRect) {
        super.init(frame: frameRect)
        configure()
    }

    required init?(coder: NSCoder) {
        nil
    }

    func configure(
        window: WindowState,
        isSelected: Bool,
        thumbnailCache: WindowThumbnailCache,
        thumbnailSize: CGSize,
        reduceMotion: Bool
    ) {
        titleLabel.stringValue = window.altTabTitle
        setAccessibilityElement(true)
        setAccessibilityRole(.button)
        setAccessibilityLabel(window.altTabTitle)
        layer?.borderWidth = isSelected ? 3 : 1
        layer?.borderColor = (isSelected ? NSColor.controlAccentColor : NSColor.separatorColor).cgColor
        if isSelected, !reduceMotion {
            alphaValue = 0.92
            animator().alphaValue = 1
        } else {
            alphaValue = 1
        }
        task?.cancel()
        task = Task { [weak self, thumbnailCache, windowID = window.id, thumbnailSize] in
            guard let image = try? await thumbnailCache.image(for: windowID, size: thumbnailSize),
                  !Task.isCancelled else {
                return
            }
            await MainActor.run {
                self?.imageView.image = NSImage(cgImage: image, size: thumbnailSize)
            }
        }
    }

    func cancelThumbnailLoad() {
        task?.cancel()
        task = nil
    }

    override func layout() {
        super.layout()
        let titleHeight: CGFloat = 28
        imageView.frame = CGRect(
            x: 8,
            y: titleHeight + 8,
            width: bounds.width - 16,
            height: bounds.height - titleHeight - 16
        )
        titleLabel.frame = CGRect(x: 10, y: 6, width: bounds.width - 20, height: titleHeight)
    }

    private func configure() {
        wantsLayer = true
        layer?.cornerRadius = 8
        layer?.backgroundColor = NSColor.windowBackgroundColor.withAlphaComponent(0.92).cgColor
        layer?.borderWidth = 1
        layer?.borderColor = NSColor.separatorColor.cgColor
        imageView.imageScaling = .scaleProportionallyUpOrDown
        imageView.wantsLayer = true
        imageView.layer?.backgroundColor = NSColor.controlBackgroundColor.cgColor
        imageView.layer?.cornerRadius = 6
        titleLabel.font = .systemFont(ofSize: 13, weight: .semibold)
        titleLabel.textColor = .labelColor
        titleLabel.lineBreakMode = .byTruncatingTail
        addSubview(imageView)
        addSubview(titleLabel)
    }
}

final class AltTabListRowView: NSView {
    private let titleLabel = NSTextField(labelWithString: "")
    private let detailLabel = NSTextField(labelWithString: "")

    override init(frame frameRect: NSRect) {
        super.init(frame: frameRect)
        configure()
    }

    required init?(coder: NSCoder) {
        nil
    }

    func configure(window: WindowState, isSelected: Bool) {
        titleLabel.stringValue = window.altTabTitle
        detailLabel.stringValue = window.bundleID ?? ""
        setAccessibilityElement(true)
        setAccessibilityRole(.button)
        setAccessibilityLabel([window.altTabTitle, window.bundleID].compactMap { $0 }.joined(separator: ", "))
        layer?.backgroundColor = (isSelected ? NSColor.selectedContentBackgroundColor : NSColor.windowBackgroundColor)
            .withAlphaComponent(0.92)
            .cgColor
    }

    override func layout() {
        super.layout()
        titleLabel.frame = CGRect(x: 12, y: 18, width: bounds.width - 24, height: 18)
        detailLabel.frame = CGRect(x: 12, y: 4, width: bounds.width - 24, height: 14)
    }

    private func configure() {
        wantsLayer = true
        layer?.cornerRadius = 7
        titleLabel.font = .systemFont(ofSize: 14, weight: .semibold)
        titleLabel.lineBreakMode = .byTruncatingTail
        detailLabel.font = .systemFont(ofSize: 11)
        detailLabel.textColor = .secondaryLabelColor
        detailLabel.lineBreakMode = .byTruncatingTail
        addSubview(titleLabel)
        addSubview(detailLabel)
    }
}

private extension WindowState {
    var altTabTitle: String {
        title?.isEmpty == false ? title ?? "" : bundleID ?? L10n.f("window %@", "fallback window title", "\(id)")
    }
}
