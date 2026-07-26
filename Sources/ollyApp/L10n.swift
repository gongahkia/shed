import Foundation

enum L10n {
    static var bundle: Bundle {
        .module
    }

    static func s(_ key: String, _ comment: String) -> String {
        string(key, comment)
    }

    static func f(_ key: String, _ comment: String, _ arguments: CVarArg...) -> String {
        String(format: string(key, comment), locale: Locale.current, arguments: arguments)
    }

    static func string(_ key: String, _ comment: String) -> String {
        NSLocalizedString(key, bundle: bundle, comment: comment)
    }

    static func format(_ key: String, _ comment: String, _ arguments: CVarArg...) -> String {
        String(format: string(key, comment), locale: Locale.current, arguments: arguments)
    }
}
