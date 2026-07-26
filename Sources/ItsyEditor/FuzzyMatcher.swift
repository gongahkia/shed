import Foundation

public struct FuzzyMatch: Equatable, Sendable {
	public let score: Int
	public let start: Int
	public let end: Int
	public let positions: [Int]
}

public enum FuzzyMatcher {
	private static let scoreMatch = 16
	private static let scoreGapStart = -3
	private static let scoreGapExtension = -1
	private static let bonusBoundary = scoreMatch / 2
	private static let bonusConsecutive = -(scoreGapStart + scoreGapExtension)
	private static let bonusFirstCharMultiplier = 2

	public static func score(candidate: String, query: String) -> FuzzyMatch? {
		let queryChars = query.fuzzyCharacters
		guard !queryChars.isEmpty else {
			return FuzzyMatch(score: 0, start: 0, end: 0, positions: [])
		}
		let candidateChars = candidate.fuzzyCharacters
		guard queryChars.count <= candidateChars.count else {
			return nil
		}

		var previous = Array<MatchState?>(repeating: nil, count: candidateChars.count)
		for queryIndex in queryChars.indices {
			var current = Array<MatchState?>(repeating: nil, count: candidateChars.count)
			for candidateIndex in candidateChars.indices where candidateChars[candidateIndex].folded == queryChars[queryIndex].folded {
				let bonus = boundaryBonus(in: candidateChars, at: candidateIndex)
				if queryIndex == queryChars.startIndex {
					let score = scoreMatch + bonus * bonusFirstCharMultiplier - candidateIndex
					current[candidateIndex] = MatchState(score: score, start: candidateIndex, positions: [candidateIndex])
					continue
				}
				var best: MatchState?
				for previousIndex in candidateChars.indices where previousIndex < candidateIndex {
					guard let previousState = previous[previousIndex] else {
						continue
					}
					let gap = candidateIndex - previousIndex - 1
					let linkScore = gap == 0 ? max(bonus, bonusConsecutive) : bonus + gapPenalty(gap)
					let state = MatchState(
						score: previousState.score + scoreMatch + linkScore,
						start: previousState.start,
						positions: previousState.positions + [candidateIndex]
					)
					if isBetter(state, than: best, endingAt: candidateIndex) {
						best = state
					}
				}
				current[candidateIndex] = best
			}
			previous = current
		}

		var bestIndex: Int?
		var bestState: MatchState?
		for index in candidateChars.indices {
			guard let state = previous[index] else {
				continue
			}
			if isBetter(state, than: bestState, endingAt: index, previousEnd: bestIndex) {
				bestState = state
				bestIndex = index
			}
		}
		guard let bestState, let bestIndex else {
			return nil
		}
		return FuzzyMatch(score: bestState.score, start: bestState.start, end: bestIndex + 1, positions: bestState.positions)
	}

	public static func ranked<C: Collection>(
		_ candidates: C,
		query: String,
		includeUnmatched: Bool = true,
		by keyPath: (C.Element) -> String
	) -> [C.Element] {
		let indexed = candidates.enumerated().map { index, candidate in
			(index: index, candidate: candidate, match: score(candidate: keyPath(candidate), query: query))
		}
		if query.isEmpty {
			return indexed.map(\.candidate)
		}
		return indexed
			.filter { includeUnmatched || $0.match != nil }
			.sorted { lhs, rhs in
				switch (lhs.match, rhs.match) {
				case let (left?, right?):
					if left.score != right.score {
						return left.score > right.score
					}
					let leftSpan = left.end - left.start
					let rightSpan = right.end - right.start
					if leftSpan != rightSpan {
						return leftSpan < rightSpan
					}
					if left.start != right.start {
						return left.start < right.start
					}
					return lhs.index < rhs.index
				case (_?, nil):
					return true
				case (nil, _?):
					return false
				case (nil, nil):
					return lhs.index < rhs.index
				}
			}
			.map(\.candidate)
	}

	private static func gapPenalty(_ gap: Int) -> Int {
		guard gap > 0 else {
			return 0
		}
		return scoreGapStart + scoreGapExtension * max(0, gap - 1)
	}

	private static func boundaryBonus(in characters: [FuzzyCharacter], at index: Int) -> Int {
		guard index > 0 else {
			return bonusBoundary + 2
		}
		let previous = characters[index - 1]
		let current = characters[index]
		if previous.kind == .white {
			return bonusBoundary + 2
		}
		if previous.kind == .delimiter || previous.kind == .nonWord {
			return bonusBoundary + 1
		}
		if previous.kind == .lower && (current.kind == .upper || current.kind == .number) {
			return bonusBoundary - 1
		}
		return 0
	}

	private static func isBetter(_ state: MatchState, than other: MatchState?, endingAt end: Int, previousEnd: Int? = nil) -> Bool {
		guard let other else {
			return true
		}
		if state.score != other.score {
			return state.score > other.score
		}
		let otherEnd = previousEnd ?? end
		let span = end - state.start
		let otherSpan = otherEnd - other.start
		if span != otherSpan {
			return span < otherSpan
		}
		return state.start < other.start
	}
}

private struct MatchState {
	let score: Int
	let start: Int
	let positions: [Int]
}

private struct FuzzyCharacter {
	let folded: String
	let kind: FuzzyCharacterKind
}

private enum FuzzyCharacterKind {
	case white
	case nonWord
	case delimiter
	case lower
	case upper
	case letter
	case number
}

private extension String {
	var fuzzyCharacters: [FuzzyCharacter] {
		map { character in
			FuzzyCharacter(folded: String(character).lowercased(), kind: character.fuzzyKind)
		}
	}
}

private extension Character {
	var fuzzyKind: FuzzyCharacterKind {
		guard let scalar = unicodeScalars.first, unicodeScalars.count == 1 else {
			return .letter
		}
		switch scalar.value {
		case 9, 10, 11, 12, 13, 32:
			return .white
		case 45, 46, 47, 58, 59, 95:
			return .delimiter
		case 48 ... 57:
			return .number
		case 65 ... 90:
			return .upper
		case 97 ... 122:
			return .lower
		default:
			return CharacterSet.alphanumerics.contains(scalar) ? .letter : .nonWord
		}
	}
}
