import Foundation

enum ShaderSource {
	static func load() throws -> String {
		guard let url = Bundle.module.url(forResource: "Shaders", withExtension: "metal") else {
			throw ShaderSourceError.missing
		}
		return try String(contentsOf: url, encoding: .utf8)
	}
}

enum ShaderSourceError: Error {
	case missing
}
