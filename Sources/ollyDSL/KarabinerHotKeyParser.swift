import Foundation

enum KarabinerHotKeyParser {
    static func parse(data: Data, sourceURL: URL) throws -> [ExternalHotKey] {
        let config = try JSONDecoder().decode(KarabinerConfig.self, from: data)
        let selectedProfiles = config.profiles.filter { $0.selected == true }
        let profiles = selectedProfiles.isEmpty ? config.profiles : selectedProfiles
        return profiles.flatMap { profile in
            profile.complexModifications.rules.flatMap { rule in
                rule.manipulators.compactMap { manipulator in
                    hotKey(from: manipulator, ruleDescription: rule.description, sourceURL: sourceURL)
                }
            }
        }
    }

    private static func hotKey(
        from manipulator: KarabinerManipulator,
        ruleDescription: String,
        sourceURL: URL
    ) -> ExternalHotKey? {
        guard manipulator.type == nil || manipulator.type == "basic" else {
            return nil
        }
        guard
            let keyName = manipulator.from.keyCode,
            let keyCode = HotKeyNameMapper.keyCode(for: keyName),
            let modifiers = HotKeyNameMapper.karabinerModifiers(manipulator.from.modifiers.mandatory)
        else {
            return nil
        }
        return ExternalHotKey(
            owner: .karabinerElements,
            chord: HotKeyChord(keyCode: keyCode, modifiers: modifiers),
            detail: "\(ruleDescription) in \(sourceURL.lastPathComponent)"
        )
    }
}

private struct KarabinerConfig: Decodable {
    let profiles: [KarabinerProfile]
}

private struct KarabinerProfile: Decodable {
    let selected: Bool?
    let complexModifications: KarabinerComplexModifications

    private enum CodingKeys: String, CodingKey {
        case selected
        case complexModifications = "complex_modifications"
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        selected = try container.decodeIfPresent(Bool.self, forKey: .selected)
        complexModifications = try container.decodeIfPresent(
            KarabinerComplexModifications.self,
            forKey: .complexModifications
        ) ?? KarabinerComplexModifications()
    }
}

private struct KarabinerComplexModifications: Decodable {
    let rules: [KarabinerRule]

    init(rules: [KarabinerRule] = []) {
        self.rules = rules
    }

    private enum CodingKeys: String, CodingKey {
        case rules
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        rules = try container.decodeIfPresent([KarabinerRule].self, forKey: .rules) ?? []
    }
}

private struct KarabinerRule: Decodable {
    let description: String
    let manipulators: [KarabinerManipulator]

    private enum CodingKeys: String, CodingKey {
        case description
        case manipulators
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        description = try container.decodeIfPresent(String.self, forKey: .description) ?? "complex modification"
        manipulators = try container.decodeIfPresent([KarabinerManipulator].self, forKey: .manipulators) ?? []
    }
}

private struct KarabinerManipulator: Decodable {
    let type: String?
    let from: KarabinerFrom

    private enum CodingKeys: String, CodingKey {
        case type
        case from
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        type = try container.decodeIfPresent(String.self, forKey: .type)
        from = try container.decodeIfPresent(KarabinerFrom.self, forKey: .from) ?? KarabinerFrom()
    }
}

private struct KarabinerFrom: Decodable {
    let keyCode: String?
    let modifiers: KarabinerModifiers

    init(keyCode: String? = nil, modifiers: KarabinerModifiers = KarabinerModifiers()) {
        self.keyCode = keyCode
        self.modifiers = modifiers
    }

    private enum CodingKeys: String, CodingKey {
        case keyCode = "key_code"
        case modifiers
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        keyCode = try container.decodeIfPresent(String.self, forKey: .keyCode)
        modifiers = try container.decodeIfPresent(KarabinerModifiers.self, forKey: .modifiers) ?? KarabinerModifiers()
    }
}

private struct KarabinerModifiers: Decodable {
    let mandatory: [String]

    init(mandatory: [String] = []) {
        self.mandatory = mandatory
    }

    private enum CodingKeys: String, CodingKey {
        case mandatory
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        mandatory = try container.decodeIfPresent([String].self, forKey: .mandatory) ?? []
    }
}
