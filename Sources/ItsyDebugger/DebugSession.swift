import Foundation
import ItsyDAP

public enum DebugSessionError: Error, Equatable, Sendable {
	case missingBody(String)
}

public struct DebugThread: Equatable, Sendable {
	public var id: Int
	public var name: String

	public init(id: Int, name: String) {
		self.id = id
		self.name = name
	}
}

public struct DebugStackFrame: Equatable, Sendable {
	public var id: Int
	public var name: String
	public var sourceName: String?
	public var sourcePath: String?
	public var line: Int
	public var column: Int
	public var endLine: Int?
	public var endColumn: Int?

	public init(id: Int, name: String, sourceName: String? = nil, sourcePath: String? = nil, line: Int, column: Int, endLine: Int? = nil, endColumn: Int? = nil) {
		self.id = id
		self.name = name
		self.sourceName = sourceName
		self.sourcePath = sourcePath
		self.line = line
		self.column = column
		self.endLine = endLine
		self.endColumn = endColumn
	}
}

public struct DebugScope: Equatable, Sendable {
	public var name: String
	public var variablesReference: Int
	public var expensive: Bool
	public var namedVariables: Int?
	public var indexedVariables: Int?

	public init(name: String, variablesReference: Int, expensive: Bool, namedVariables: Int? = nil, indexedVariables: Int? = nil) {
		self.name = name
		self.variablesReference = variablesReference
		self.expensive = expensive
		self.namedVariables = namedVariables
		self.indexedVariables = indexedVariables
	}
}

public struct DebugVariable: Equatable, Sendable {
	public var name: String
	public var value: String
	public var type: String?
	public var variablesReference: Int
	public var namedVariables: Int?
	public var indexedVariables: Int?
	public var memoryReference: String?

	public init(name: String, value: String, type: String? = nil, variablesReference: Int, namedVariables: Int? = nil, indexedVariables: Int? = nil, memoryReference: String? = nil) {
		self.name = name
		self.value = value
		self.type = type
		self.variablesReference = variablesReference
		self.namedVariables = namedVariables
		self.indexedVariables = indexedVariables
		self.memoryReference = memoryReference
	}
}

public struct DebugValue: Equatable, Sendable {
	public var result: String
	public var type: String?
	public var variablesReference: Int
	public var namedVariables: Int?
	public var indexedVariables: Int?
	public var memoryReference: String?

	public init(result: String, type: String? = nil, variablesReference: Int, namedVariables: Int? = nil, indexedVariables: Int? = nil, memoryReference: String? = nil) {
		self.result = result
		self.type = type
		self.variablesReference = variablesReference
		self.namedVariables = namedVariables
		self.indexedVariables = indexedVariables
		self.memoryReference = memoryReference
	}
}

public actor DebugSession {
	public private(set) var threads: [DebugThread] = []
	public private(set) var focusedThreadID: Int?
	public private(set) var focusedFrameID: Int?

	private let client: DAPClientSession
	private let encoder = JSONEncoder()
	private let decoder = JSONDecoder()

	public init(client: DAPClientSession) {
		self.client = client
	}

	@discardableResult
	public func refreshThreads() async throws -> [DebugThread] {
		let response = try await client.sendRequest(command: DAPCommand.threads)
		let body = try responseBody(response, as: DAPThreadsResponseBody.self)
		let mapped = body.threads.map { DebugThread(id: $0.id, name: $0.name) }
		threads = mapped
		if focusedThreadID.map({ id in mapped.contains { $0.id == id } }) != true {
			focusedThreadID = mapped.first?.id
		}
		return mapped
	}

	public func stackFrames(for threadID: Int) async throws -> [DebugStackFrame] {
		let response = try await client.sendRequest(
			command: DAPCommand.stackTrace,
			arguments: try DAPAny(encoding: DAPStackTraceArguments(threadId: threadID))
		)
		let body = try responseBody(response, as: DAPStackTraceResponseBody.self)
		let mapped = body.stackFrames.map { frame in
			DebugStackFrame(
				id: frame.id,
				name: frame.name,
				sourceName: frame.source?.name,
				sourcePath: frame.source?.path,
				line: frame.line,
				column: frame.column,
				endLine: frame.endLine,
				endColumn: frame.endColumn
			)
		}
		focusedThreadID = threadID
		focusedFrameID = mapped.first?.id
		return mapped
	}

	public func focus(threadID: Int, frameID: Int?) {
		focusedThreadID = threadID
		focusedFrameID = frameID
	}

	public func scopes(for frameID: Int) async throws -> [DebugScope] {
		let response = try await client.sendRequest(
			command: DAPCommand.scopes,
			arguments: try DAPAny(encoding: DAPScopesArguments(frameId: frameID))
		)
		let body = try responseBody(response, as: DAPScopesResponseBody.self)
		focusedFrameID = frameID
		return body.scopes.map {
			DebugScope(
				name: $0.name,
				variablesReference: $0.variablesReference,
				expensive: $0.expensive,
				namedVariables: $0.namedVariables,
				indexedVariables: $0.indexedVariables
			)
		}
	}

	public func variables(for variablesReference: Int) async throws -> [DebugVariable] {
		let response = try await client.sendRequest(
			command: DAPCommand.variables,
			arguments: try DAPAny(encoding: DAPVariablesArguments(variablesReference: variablesReference))
		)
		let body = try responseBody(response, as: DAPVariablesResponseBody.self)
		return body.variables.map {
			DebugVariable(
				name: $0.name,
				value: $0.value,
				type: $0.type,
				variablesReference: $0.variablesReference,
				namedVariables: $0.namedVariables,
				indexedVariables: $0.indexedVariables,
				memoryReference: $0.memoryReference
			)
		}
	}

	public func evaluate(expression: String, frameID: Int? = nil, context: String? = nil) async throws -> DebugValue {
		let response = try await client.sendRequest(
			command: DAPCommand.evaluate,
			arguments: try DAPAny(encoding: DAPEvaluateArguments(expression: expression, frameId: frameID, context: context))
		)
		let body = try responseBody(response, as: DAPEvaluateResponseBody.self)
		return DebugValue(
			result: body.result,
			type: body.type,
			variablesReference: body.variablesReference,
			namedVariables: body.namedVariables,
			indexedVariables: body.indexedVariables,
			memoryReference: body.memoryReference
		)
	}

	private func responseBody<Value: Decodable>(_ response: DAPResponse, as type: Value.Type) throws -> Value {
		guard let body = response.body else {
			throw DebugSessionError.missingBody(response.command)
		}
		return try decoder.decode(type, from: encoder.encode(body))
	}
}
