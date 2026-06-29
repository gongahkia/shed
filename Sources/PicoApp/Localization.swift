import Foundation

enum L10n {
	static let locale = Locale(identifier: "en")

	static func string(_ value: String.LocalizationValue) -> String {
		String(localized: value, bundle: .main, locale: locale)
	}
}
