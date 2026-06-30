import Foundation
import ollyCore
import ollyKit

/// Purpose: Reports invalid workspace tag declarations before they become runtime state.
/// Parameters: Inspect the associated duplicate name or tag count.
/// Example: `XCTAssertThrowsError(try Workspaces(validating: declarations))`
/// See also: `Workspaces`, `NamedTagDeclaration`.
public enum WorkspacesError: Error, Equatable, Sendable {
    case duplicateTagName(String)
    case tooManyTags(Int)
}

/// Purpose: Captures a user-facing workspace tag name before assigning its numeric tag.
/// Parameters: Pass a static tag name.
/// Example: `Tag.named("web")`
/// See also: `NamedTag`, `Workspaces`.
public struct NamedTagDeclaration: Codable, Equatable, Sendable {
    public let name: String
    public let engineID: LayoutEngineID?
    public let launchBundleIDs: [String]
    public let rawHandler: RawDSLBlock<Void>?

    public init(
        _ name: StaticString,
        engineID: LayoutEngineID? = nil,
        launchBundleIDs: [String] = [],
        rawHandler: RawDSLBlock<Void>? = nil
    ) {
        self.name = String(describing: name)
        self.engineID = engineID
        self.launchBundleIDs = launchBundleIDs
        self.rawHandler = rawHandler
    }

    init(
        uncheckedName name: String,
        engineID: LayoutEngineID? = nil,
        launchBundleIDs: [String] = [],
        rawHandler: RawDSLBlock<Void>? = nil
    ) {
        self.name = name
        self.engineID = engineID
        self.launchBundleIDs = launchBundleIDs
        self.rawHandler = rawHandler
    }

    public static func raw(
        _ name: StaticString,
        label: String? = nil,
        _ body: @escaping RawDSLHandler
    ) -> NamedTagDeclaration {
        let tagName = String(describing: name)
        return NamedTagDeclaration(uncheckedName: tagName, rawHandler: RawDSLBlock(label ?? tagName, body))
    }

    public func engine(_ engineID: LayoutEngineID) -> NamedTagDeclaration {
        NamedTagDeclaration(
            uncheckedName: name,
            engineID: engineID,
            launchBundleIDs: launchBundleIDs,
            rawHandler: rawHandler
        )
    }

    public func launch(_ bundleID: String) -> NamedTagDeclaration {
        NamedTagDeclaration(
            uncheckedName: name,
            engineID: engineID,
            launchBundleIDs: launchBundleIDs + [bundleID],
            rawHandler: rawHandler
        )
    }

    public func runRaw(context: RawDSLContext) {
        rawHandler?(context)
    }

    public static func == (lhs: NamedTagDeclaration, rhs: NamedTagDeclaration) -> Bool {
        lhs.name == rhs.name && lhs.engineID == rhs.engineID && lhs.launchBundleIDs == rhs.launchBundleIDs
    }

    enum CodingKeys: String, CodingKey {
        case name
        case engineID
        case launchBundleIDs
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        self.init(
            uncheckedName: try container.decode(String.self, forKey: .name),
            engineID: try container.decodeIfPresent(LayoutEngineID.self, forKey: .engineID),
            launchBundleIDs: try container.decodeIfPresent([String].self, forKey: .launchBundleIDs) ?? []
        )
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(name, forKey: .name)
        try container.encodeIfPresent(engineID, forKey: .engineID)
        try container.encodeIfPresent(launchBundleIDs.nonEmpty, forKey: .launchBundleIDs)
    }
}

/// Purpose: Binds a display name to a concrete River-style tag bit.
/// Parameters: Pass the visible name and assigned `Tag`.
/// Example: `NamedTag(name: "code", tag: try Tag(index: 1))`
/// See also: `NamedTagDeclaration`, `Workspaces`.
public struct NamedTag: Codable, Equatable, Sendable {
    public let name: String
    public let tag: Tag

    public init(name: String, tag: Tag) {
        self.name = name
        self.tag = tag
    }
}

public struct WorkspaceEngineBinding: Codable, Equatable, Sendable {
    public let displayID: DisplayID?
    public let tag: Tag
    public let engineID: LayoutEngineID

    public init(displayID: DisplayID? = nil, tag: Tag, engineID: LayoutEngineID) {
        self.displayID = displayID
        self.tag = tag
        self.engineID = engineID
    }
}

public struct WorkspaceLaunchBinding: Codable, Equatable, Sendable {
    public let displayID: DisplayID?
    public let tag: Tag
    public let bundleID: String

    public init(displayID: DisplayID? = nil, tag: Tag, bundleID: String) {
        self.displayID = displayID
        self.tag = tag
        self.bundleID = bundleID
    }
}

public struct DisplayWorkspaceDeclaration: Equatable, Sendable {
    public let displayID: DisplayID
    public let declarations: [NamedTagDeclaration]

    public init(displayID: DisplayID, declarations: [NamedTagDeclaration]) {
        self.displayID = displayID
        self.declarations = declarations
    }
}

public struct WorkspaceDisplayAssignment: Codable, Equatable, Sendable {
    public let displayID: DisplayID
    public let tags: [Tag]

    public init(displayID: DisplayID, tags: [Tag]) {
        self.displayID = displayID
        self.tags = tags
    }
}

public enum WorkspaceDeclaration: Equatable, Sendable {
    case tag(NamedTagDeclaration)
    case display(DisplayWorkspaceDeclaration)
}

public extension Tag {
    static func named(_ name: StaticString) -> NamedTagDeclaration {
        NamedTagDeclaration(name)
    }
}

public func display(
    _ displayID: DisplayID,
    @DisplayWorkspacesBuilder _ build: () -> [NamedTagDeclaration]
) -> DisplayWorkspaceDeclaration {
    DisplayWorkspaceDeclaration(displayID: displayID, declarations: build())
}

/// Purpose: Groups named workspace tags for the top-level config.
/// Parameters: Pass resolved tags or build named tag declarations.
/// Example: `Workspaces { Tag.named("web"); Tag.named("code") }`
/// See also: `NamedTagDeclaration`, `WorkspacesBuilder`.
public struct Workspaces: Codable, Equatable, Sendable {
    public let tags: [NamedTag]
    public let engineBindings: [WorkspaceEngineBinding]
    public let launchBindings: [WorkspaceLaunchBinding]
    public let displayAssignments: [WorkspaceDisplayAssignment]

    public init(
        _ tags: [NamedTag] = [],
        engineBindings: [WorkspaceEngineBinding] = [],
        launchBindings: [WorkspaceLaunchBinding] = [],
        displayAssignments: [WorkspaceDisplayAssignment] = []
    ) {
        self.tags = tags
        self.engineBindings = engineBindings
        self.launchBindings = launchBindings
        self.displayAssignments = displayAssignments
    }

    public init(@WorkspacesBuilder _ build: () -> [WorkspaceDeclaration]) {
        do {
            self = try Workspaces(validating: build())
        } catch {
            preconditionFailure(String(describing: error))
        }
    }

    public init(validating declarations: [NamedTagDeclaration]) throws {
        try self.init(validating: declarations.map(WorkspaceDeclaration.tag))
    }

    public init(validating declarations: [WorkspaceDeclaration]) throws {
        let resolved = try Self.resolve(declarations)
        self.init(
            resolved.tags,
            engineBindings: resolved.engineBindings,
            launchBindings: resolved.launchBindings,
            displayAssignments: resolved.displayAssignments
        )
    }

    public func tag(named name: String) -> Tag? {
        tags.first { $0.name == name }?.tag
    }

    public func engineBinding(for tag: Tag, on displayID: DisplayID) -> LayoutEngineID? {
        let exact = engineBindings.first { $0.displayID == displayID && $0.tag == tag }?.engineID
        return exact ?? engineBindings.first { $0.displayID == nil && $0.tag == tag }?.engineID
    }

    public func engineBindings(on displayID: DisplayID) -> [WorkspaceEngineBinding] {
        var enginesByTag: [Tag: LayoutEngineID] = [:]
        for binding in engineBindings where binding.displayID == nil {
            enginesByTag[binding.tag] = binding.engineID
        }
        for binding in engineBindings where binding.displayID == displayID {
            enginesByTag[binding.tag] = binding.engineID
        }
        return enginesByTag.map { tag, engineID in
            WorkspaceEngineBinding(displayID: displayID, tag: tag, engineID: engineID)
        }.sorted { lhs, rhs in
            lhs.tag < rhs.tag
        }
    }

    public func launchBundleIDs(for tag: Tag, on displayID: DisplayID) -> [String] {
        let bundles = launchBindings
            .filter { ($0.displayID == nil || $0.displayID == displayID) && $0.tag == tag }
            .map(\.bundleID)
        var seen = Set<String>()
        return bundles.filter { seen.insert($0).inserted }
    }

    public func initialTags(on displayID: DisplayID) -> TagSet? {
        let tags = displayAssignments
            .filter { $0.displayID == displayID }
            .flatMap(\.tags)
        return tags.isEmpty ? nil : TagSet(tags)
    }

    private enum CodingKeys: String, CodingKey {
        case tags
        case engineBindings
        case launchBindings
        case displayAssignments
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        self.init(
            try container.decodeIfPresent([NamedTag].self, forKey: .tags) ?? [],
            engineBindings: try container.decodeIfPresent(
                [WorkspaceEngineBinding].self,
                forKey: .engineBindings
            ) ?? [],
            launchBindings: try container.decodeIfPresent(
                [WorkspaceLaunchBinding].self,
                forKey: .launchBindings
            ) ?? [],
            displayAssignments: try container.decodeIfPresent(
                [WorkspaceDisplayAssignment].self,
                forKey: .displayAssignments
            ) ?? []
        )
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(tags, forKey: .tags)
        try container.encode(engineBindings, forKey: .engineBindings)
        try container.encode(launchBindings, forKey: .launchBindings)
        try container.encode(displayAssignments, forKey: .displayAssignments)
    }

    private struct WorkspaceResolution {
        var tags: [NamedTag] = []
        var engineBindings: [WorkspaceEngineBinding] = []
        var launchBindings: [WorkspaceLaunchBinding] = []
        var displayAssignments: [WorkspaceDisplayAssignment] = []
    }

    private static func resolve(_ declarations: [WorkspaceDeclaration]) throws -> WorkspaceResolution {
        var tagsByName: [String: Tag] = [:]
        var seenNames = Set<String>()
        var resolution = WorkspaceResolution()
        for declaration in declarations {
            switch declaration {
            case let .tag(tagDeclaration):
                guard seenNames.insert(tagDeclaration.name).inserted else {
                    throw WorkspacesError.duplicateTagName(tagDeclaration.name)
                }
                let tag = try resolveTag(tagDeclaration, tagsByName: &tagsByName, tags: &resolution.tags)
                if let engineID = tagDeclaration.engineID {
                    resolution.engineBindings.append(WorkspaceEngineBinding(tag: tag, engineID: engineID))
                }
                appendLaunchBindings(tagDeclaration.launchBundleIDs, tag: tag, displayID: nil, to: &resolution)
            case let .display(displayDeclaration):
                var seenDisplayNames = Set<String>()
                var displayTags: [Tag] = []
                for tagDeclaration in displayDeclaration.declarations {
                    guard seenDisplayNames.insert(tagDeclaration.name).inserted else {
                        throw WorkspacesError.duplicateTagName(tagDeclaration.name)
                    }
                    let tag = try resolveTag(tagDeclaration, tagsByName: &tagsByName, tags: &resolution.tags)
                    displayTags.append(tag)
                    if let engineID = tagDeclaration.engineID {
                        resolution.engineBindings.append(
                            WorkspaceEngineBinding(
                                displayID: displayDeclaration.displayID,
                                tag: tag,
                                engineID: engineID
                            )
                        )
                    }
                    appendLaunchBindings(
                        tagDeclaration.launchBundleIDs,
                        tag: tag,
                        displayID: displayDeclaration.displayID,
                        to: &resolution
                    )
                }
                if !displayTags.isEmpty {
                    resolution.displayAssignments.append(
                        WorkspaceDisplayAssignment(displayID: displayDeclaration.displayID, tags: displayTags)
                    )
                }
            }
        }
        return resolution
    }

    private static func resolveTag(
        _ declaration: NamedTagDeclaration,
        tagsByName: inout [String: Tag],
        tags: inout [NamedTag]
    ) throws -> Tag {
        if let tag = tagsByName[declaration.name] {
            return tag
        }
        guard tags.count < 64 else {
            throw WorkspacesError.tooManyTags(tags.count + 1)
        }
        let tag = try Tag(index: tags.count)
        tagsByName[declaration.name] = tag
        tags.append(NamedTag(name: declaration.name, tag: tag))
        return tag
    }

    private static func appendLaunchBindings(
        _ bundleIDs: [String],
        tag: Tag,
        displayID: DisplayID?,
        to resolution: inout WorkspaceResolution
    ) {
        for bundleID in bundleIDs {
            resolution.launchBindings.append(
                WorkspaceLaunchBinding(displayID: displayID, tag: tag, bundleID: bundleID)
            )
        }
    }
}

private extension Array {
    var nonEmpty: [Element]? {
        isEmpty ? nil : self
    }
}
