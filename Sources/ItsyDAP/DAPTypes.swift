import Foundation

public enum DAPCommand {
	public static let initialize = "initialize"
	public static let launch = "launch"
	public static let attach = "attach"
	public static let configurationDone = "configurationDone"
	public static let disconnect = "disconnect"
	public static let terminate = "terminate"
	public static let setBreakpoints = "setBreakpoints"
	public static let setFunctionBreakpoints = "setFunctionBreakpoints"
	public static let setExceptionBreakpoints = "setExceptionBreakpoints"
	public static let setDataBreakpoints = "setDataBreakpoints"
	public static let threads = "threads"
	public static let stackTrace = "stackTrace"
	public static let scopes = "scopes"
	public static let variables = "variables"
	public static let setVariable = "setVariable"
	public static let continueExecution = "continue"
	public static let next = "next"
	public static let stepIn = "stepIn"
	public static let stepOut = "stepOut"
	public static let pause = "pause"
	public static let reverseContinue = "reverseContinue"
	public static let restart = "restart"
	public static let evaluate = "evaluate"
}

public enum DAPEvent {
	public static let initialized = "initialized"
	public static let stopped = "stopped"
	public static let continued = "continued"
	public static let exited = "exited"
	public static let terminated = "terminated"
	public static let thread = "thread"
	public static let output = "output"
	public static let breakpoint = "breakpoint"
	public static let module = "module"
	public static let loadedSource = "loadedSource"
	public static let process = "process"
	public static let capabilities = "capabilities"
	public static let invalidated = "invalidated"
}

public struct DAPInitializeRequestArguments: Codable, Equatable, Sendable {
	public var clientID: String?
	public var clientName: String?
	public var adapterID: String
	public var locale: String?
	public var linesStartAt1: Bool?
	public var columnsStartAt1: Bool?
	public var pathFormat: String?
	public var supportsVariableType: Bool?
	public var supportsVariablePaging: Bool?
	public var supportsRunInTerminalRequest: Bool?
	public var supportsMemoryReferences: Bool?
	public var supportsProgressReporting: Bool?
	public var supportsInvalidatedEvent: Bool?

	public init(
		clientID: String? = nil,
		clientName: String? = nil,
		adapterID: String,
		locale: String? = nil,
		linesStartAt1: Bool? = nil,
		columnsStartAt1: Bool? = nil,
		pathFormat: String? = nil,
		supportsVariableType: Bool? = nil,
		supportsVariablePaging: Bool? = nil,
		supportsRunInTerminalRequest: Bool? = nil,
		supportsMemoryReferences: Bool? = nil,
		supportsProgressReporting: Bool? = nil,
		supportsInvalidatedEvent: Bool? = nil
	) {
		self.clientID = clientID
		self.clientName = clientName
		self.adapterID = adapterID
		self.locale = locale
		self.linesStartAt1 = linesStartAt1
		self.columnsStartAt1 = columnsStartAt1
		self.pathFormat = pathFormat
		self.supportsVariableType = supportsVariableType
		self.supportsVariablePaging = supportsVariablePaging
		self.supportsRunInTerminalRequest = supportsRunInTerminalRequest
		self.supportsMemoryReferences = supportsMemoryReferences
		self.supportsProgressReporting = supportsProgressReporting
		self.supportsInvalidatedEvent = supportsInvalidatedEvent
	}
}

public struct DAPCapabilities: Codable, Equatable, Sendable {
	public var supportsConfigurationDoneRequest: Bool?
	public var supportsFunctionBreakpoints: Bool?
	public var supportsConditionalBreakpoints: Bool?
	public var supportsHitConditionalBreakpoints: Bool?
	public var supportsEvaluateForHovers: Bool?
	public var supportsStepBack: Bool?
	public var supportsSetVariable: Bool?
	public var supportsRestartFrame: Bool?
	public var supportsGotoTargetsRequest: Bool?
	public var supportsStepInTargetsRequest: Bool?
	public var supportsCompletionsRequest: Bool?
	public var supportsModulesRequest: Bool?
	public var supportsRestartRequest: Bool?
	public var supportsExceptionInfoRequest: Bool?
	public var supportsExceptionOptions: Bool?
	public var supportsDelayedStackTraceLoading: Bool?
	public var supportsLoadedSourcesRequest: Bool?
	public var supportsLogPoints: Bool?
	public var supportsTerminateThreadsRequest: Bool?
	public var supportsSetExpression: Bool?
	public var supportsTerminateRequest: Bool?
	public var supportsDataBreakpoints: Bool?
	public var supportsDataBreakpointBytes: Bool?
	public var supportsValueFormattingOptions: Bool?
	public var supportsExceptionFilterOptions: Bool?
	public var supportTerminateDebuggee: Bool?
	public var supportSuspendDebuggee: Bool?
	public var supportsReadMemoryRequest: Bool?
	public var supportsWriteMemoryRequest: Bool?
	public var supportsDisassembleRequest: Bool?
	public var supportsCancelRequest: Bool?
	public var supportsBreakpointLocationsRequest: Bool?
	public var supportsSteppingGranularity: Bool?
	public var supportsInstructionBreakpoints: Bool?
	public var supportsSingleThreadExecutionRequests: Bool?

	public init(
		supportsConfigurationDoneRequest: Bool? = nil,
		supportsFunctionBreakpoints: Bool? = nil,
		supportsConditionalBreakpoints: Bool? = nil,
		supportsHitConditionalBreakpoints: Bool? = nil,
		supportsEvaluateForHovers: Bool? = nil,
		supportsStepBack: Bool? = nil,
		supportsSetVariable: Bool? = nil,
		supportsRestartFrame: Bool? = nil,
		supportsGotoTargetsRequest: Bool? = nil,
		supportsStepInTargetsRequest: Bool? = nil,
		supportsCompletionsRequest: Bool? = nil,
		supportsModulesRequest: Bool? = nil,
		supportsRestartRequest: Bool? = nil,
		supportsExceptionInfoRequest: Bool? = nil,
		supportsExceptionOptions: Bool? = nil,
		supportsDelayedStackTraceLoading: Bool? = nil,
		supportsLoadedSourcesRequest: Bool? = nil,
		supportsLogPoints: Bool? = nil,
		supportsTerminateThreadsRequest: Bool? = nil,
		supportsSetExpression: Bool? = nil,
		supportsTerminateRequest: Bool? = nil,
		supportsDataBreakpoints: Bool? = nil,
		supportsDataBreakpointBytes: Bool? = nil,
		supportsValueFormattingOptions: Bool? = nil,
		supportsExceptionFilterOptions: Bool? = nil,
		supportTerminateDebuggee: Bool? = nil,
		supportSuspendDebuggee: Bool? = nil,
		supportsReadMemoryRequest: Bool? = nil,
		supportsWriteMemoryRequest: Bool? = nil,
		supportsDisassembleRequest: Bool? = nil,
		supportsCancelRequest: Bool? = nil,
		supportsBreakpointLocationsRequest: Bool? = nil,
		supportsSteppingGranularity: Bool? = nil,
		supportsInstructionBreakpoints: Bool? = nil,
		supportsSingleThreadExecutionRequests: Bool? = nil
	) {
		self.supportsConfigurationDoneRequest = supportsConfigurationDoneRequest
		self.supportsFunctionBreakpoints = supportsFunctionBreakpoints
		self.supportsConditionalBreakpoints = supportsConditionalBreakpoints
		self.supportsHitConditionalBreakpoints = supportsHitConditionalBreakpoints
		self.supportsEvaluateForHovers = supportsEvaluateForHovers
		self.supportsStepBack = supportsStepBack
		self.supportsSetVariable = supportsSetVariable
		self.supportsRestartFrame = supportsRestartFrame
		self.supportsGotoTargetsRequest = supportsGotoTargetsRequest
		self.supportsStepInTargetsRequest = supportsStepInTargetsRequest
		self.supportsCompletionsRequest = supportsCompletionsRequest
		self.supportsModulesRequest = supportsModulesRequest
		self.supportsRestartRequest = supportsRestartRequest
		self.supportsExceptionInfoRequest = supportsExceptionInfoRequest
		self.supportsExceptionOptions = supportsExceptionOptions
		self.supportsDelayedStackTraceLoading = supportsDelayedStackTraceLoading
		self.supportsLoadedSourcesRequest = supportsLoadedSourcesRequest
		self.supportsLogPoints = supportsLogPoints
		self.supportsTerminateThreadsRequest = supportsTerminateThreadsRequest
		self.supportsSetExpression = supportsSetExpression
		self.supportsTerminateRequest = supportsTerminateRequest
		self.supportsDataBreakpoints = supportsDataBreakpoints
		self.supportsDataBreakpointBytes = supportsDataBreakpointBytes
		self.supportsValueFormattingOptions = supportsValueFormattingOptions
		self.supportsExceptionFilterOptions = supportsExceptionFilterOptions
		self.supportTerminateDebuggee = supportTerminateDebuggee
		self.supportSuspendDebuggee = supportSuspendDebuggee
		self.supportsReadMemoryRequest = supportsReadMemoryRequest
		self.supportsWriteMemoryRequest = supportsWriteMemoryRequest
		self.supportsDisassembleRequest = supportsDisassembleRequest
		self.supportsCancelRequest = supportsCancelRequest
		self.supportsBreakpointLocationsRequest = supportsBreakpointLocationsRequest
		self.supportsSteppingGranularity = supportsSteppingGranularity
		self.supportsInstructionBreakpoints = supportsInstructionBreakpoints
		self.supportsSingleThreadExecutionRequests = supportsSingleThreadExecutionRequests
	}
}

public typealias DAPInitializeResponseBody = DAPCapabilities

public struct DAPConfigurationDoneArguments: Codable, Equatable, Sendable {
	public init() {}
}

public struct DAPLaunchRequestArguments: Codable, Equatable, Sendable {
	public var noDebug: Bool?
	public var restartData: DAPAny?
	public var program: String?
	public var args: [String]?
	public var cwd: String?
	public var env: [String: String]?
	public var stopOnEntry: Bool?

	private enum CodingKeys: String, CodingKey {
		case noDebug
		case restartData = "__restart"
		case program
		case args
		case cwd
		case env
		case stopOnEntry
	}

	public init(noDebug: Bool? = nil, restartData: DAPAny? = nil, program: String? = nil, args: [String]? = nil, cwd: String? = nil, env: [String: String]? = nil, stopOnEntry: Bool? = nil) {
		self.noDebug = noDebug
		self.restartData = restartData
		self.program = program
		self.args = args
		self.cwd = cwd
		self.env = env
		self.stopOnEntry = stopOnEntry
	}
}

public struct DAPAttachRequestArguments: Codable, Equatable, Sendable {
	public var restartData: DAPAny?
	public var pid: Int?
	public var program: String?

	private enum CodingKeys: String, CodingKey {
		case restartData = "__restart"
		case pid
		case program
	}

	public init(restartData: DAPAny? = nil, pid: Int? = nil, program: String? = nil) {
		self.restartData = restartData
		self.pid = pid
		self.program = program
	}
}

public struct DAPRestartArguments: Codable, Equatable, Sendable {
	public var arguments: DAPAny?

	public init(arguments: DAPAny? = nil) {
		self.arguments = arguments
	}
}

public struct DAPDisconnectArguments: Codable, Equatable, Sendable {
	public var restart: Bool?
	public var terminateDebuggee: Bool?
	public var suspendDebuggee: Bool?

	public init(restart: Bool? = nil, terminateDebuggee: Bool? = nil, suspendDebuggee: Bool? = nil) {
		self.restart = restart
		self.terminateDebuggee = terminateDebuggee
		self.suspendDebuggee = suspendDebuggee
	}
}

public struct DAPTerminateArguments: Codable, Equatable, Sendable {
	public var restart: Bool?

	public init(restart: Bool? = nil) {
		self.restart = restart
	}
}

public struct DAPErrorMessage: Codable, Equatable, Sendable {
	public var id: Int
	public var format: String
	public var variables: [String: String]?
	public var sendTelemetry: Bool?
	public var showUser: Bool?
	public var url: String?
	public var urlLabel: String?

	public init(id: Int, format: String, variables: [String: String]? = nil, sendTelemetry: Bool? = nil, showUser: Bool? = nil, url: String? = nil, urlLabel: String? = nil) {
		self.id = id
		self.format = format
		self.variables = variables
		self.sendTelemetry = sendTelemetry
		self.showUser = showUser
		self.url = url
		self.urlLabel = urlLabel
	}
}

public struct DAPErrorResponseBody: Codable, Equatable, Sendable {
	public var error: DAPErrorMessage

	public init(error: DAPErrorMessage) {
		self.error = error
	}
}

public struct DAPSource: Codable, Equatable, Sendable {
	public var name: String?
	public var path: String?
	public var sourceReference: Int?

	public init(name: String? = nil, path: String? = nil, sourceReference: Int? = nil) {
		self.name = name
		self.path = path
		self.sourceReference = sourceReference
	}
}

public struct DAPSourceBreakpoint: Codable, Equatable, Sendable {
	public var line: Int
	public var column: Int?
	public var condition: String?
	public var hitCondition: String?
	public var logMessage: String?

	public init(line: Int, column: Int? = nil, condition: String? = nil, hitCondition: String? = nil, logMessage: String? = nil) {
		self.line = line
		self.column = column
		self.condition = condition
		self.hitCondition = hitCondition
		self.logMessage = logMessage
	}
}

public struct DAPSetBreakpointsArguments: Codable, Equatable, Sendable {
	public var source: DAPSource
	public var breakpoints: [DAPSourceBreakpoint]?
	public var lines: [Int]?
	public var sourceModified: Bool?

	public init(source: DAPSource, breakpoints: [DAPSourceBreakpoint]? = nil, lines: [Int]? = nil, sourceModified: Bool? = nil) {
		self.source = source
		self.breakpoints = breakpoints
		self.lines = lines
		self.sourceModified = sourceModified
	}
}

public struct DAPBreakpoint: Codable, Equatable, Sendable {
	public var id: Int?
	public var verified: Bool
	public var message: String?
	public var source: DAPSource?
	public var line: Int?
	public var column: Int?
	public var endLine: Int?
	public var endColumn: Int?

	public init(id: Int? = nil, verified: Bool, message: String? = nil, source: DAPSource? = nil, line: Int? = nil, column: Int? = nil, endLine: Int? = nil, endColumn: Int? = nil) {
		self.id = id
		self.verified = verified
		self.message = message
		self.source = source
		self.line = line
		self.column = column
		self.endLine = endLine
		self.endColumn = endColumn
	}
}

public struct DAPSetBreakpointsResponseBody: Codable, Equatable, Sendable {
	public var breakpoints: [DAPBreakpoint]

	public init(breakpoints: [DAPBreakpoint]) {
		self.breakpoints = breakpoints
	}
}

public struct DAPFunctionBreakpoint: Codable, Equatable, Sendable {
	public var name: String
	public var condition: String?
	public var hitCondition: String?

	public init(name: String, condition: String? = nil, hitCondition: String? = nil) {
		self.name = name
		self.condition = condition
		self.hitCondition = hitCondition
	}
}

public struct DAPSetFunctionBreakpointsArguments: Codable, Equatable, Sendable {
	public var breakpoints: [DAPFunctionBreakpoint]

	public init(breakpoints: [DAPFunctionBreakpoint]) {
		self.breakpoints = breakpoints
	}
}

public typealias DAPSetFunctionBreakpointsResponseBody = DAPSetBreakpointsResponseBody

public struct DAPExceptionFilterOptions: Codable, Equatable, Sendable {
	public var filterId: String
	public var condition: String?

	public init(filterId: String, condition: String? = nil) {
		self.filterId = filterId
		self.condition = condition
	}
}

public struct DAPExceptionPathSegment: Codable, Equatable, Sendable {
	public var negate: Bool?
	public var names: [String]

	public init(negate: Bool? = nil, names: [String]) {
		self.negate = negate
		self.names = names
	}
}

public struct DAPExceptionOptions: Codable, Equatable, Sendable {
	public var path: [DAPExceptionPathSegment]?
	public var breakMode: String

	public init(path: [DAPExceptionPathSegment]? = nil, breakMode: String) {
		self.path = path
		self.breakMode = breakMode
	}
}

public struct DAPSetExceptionBreakpointsArguments: Codable, Equatable, Sendable {
	public var filters: [String]
	public var filterOptions: [DAPExceptionFilterOptions]?
	public var exceptionOptions: [DAPExceptionOptions]?

	public init(filters: [String], filterOptions: [DAPExceptionFilterOptions]? = nil, exceptionOptions: [DAPExceptionOptions]? = nil) {
		self.filters = filters
		self.filterOptions = filterOptions
		self.exceptionOptions = exceptionOptions
	}
}

public struct DAPSetExceptionBreakpointsResponseBody: Codable, Equatable, Sendable {
	public var breakpoints: [DAPBreakpoint]?

	public init(breakpoints: [DAPBreakpoint]? = nil) {
		self.breakpoints = breakpoints
	}
}

public struct DAPDataBreakpoint: Codable, Equatable, Sendable {
	public var dataId: String
	public var accessType: String?
	public var condition: String?
	public var hitCondition: String?

	public init(dataId: String, accessType: String? = nil, condition: String? = nil, hitCondition: String? = nil) {
		self.dataId = dataId
		self.accessType = accessType
		self.condition = condition
		self.hitCondition = hitCondition
	}
}

public struct DAPSetDataBreakpointsArguments: Codable, Equatable, Sendable {
	public var breakpoints: [DAPDataBreakpoint]

	public init(breakpoints: [DAPDataBreakpoint]) {
		self.breakpoints = breakpoints
	}
}

public typealias DAPSetDataBreakpointsResponseBody = DAPSetBreakpointsResponseBody

public struct DAPThread: Codable, Equatable, Sendable {
	public var id: Int
	public var name: String

	public init(id: Int, name: String) {
		self.id = id
		self.name = name
	}
}

public struct DAPThreadsResponseBody: Codable, Equatable, Sendable {
	public var threads: [DAPThread]

	public init(threads: [DAPThread]) {
		self.threads = threads
	}
}

public struct DAPValueFormat: Codable, Equatable, Sendable {
	public var hex: Bool?

	public init(hex: Bool? = nil) {
		self.hex = hex
	}
}

public struct DAPStackFrameFormat: Codable, Equatable, Sendable {
	public var hex: Bool?
	public var parameters: Bool?
	public var parameterTypes: Bool?
	public var parameterNames: Bool?
	public var parameterValues: Bool?
	public var line: Bool?
	public var module: Bool?
	public var includeAll: Bool?

	public init(hex: Bool? = nil, parameters: Bool? = nil, parameterTypes: Bool? = nil, parameterNames: Bool? = nil, parameterValues: Bool? = nil, line: Bool? = nil, module: Bool? = nil, includeAll: Bool? = nil) {
		self.hex = hex
		self.parameters = parameters
		self.parameterTypes = parameterTypes
		self.parameterNames = parameterNames
		self.parameterValues = parameterValues
		self.line = line
		self.module = module
		self.includeAll = includeAll
	}
}

public struct DAPStackTraceArguments: Codable, Equatable, Sendable {
	public var threadId: Int
	public var startFrame: Int?
	public var levels: Int?
	public var format: DAPStackFrameFormat?

	public init(threadId: Int, startFrame: Int? = nil, levels: Int? = nil, format: DAPStackFrameFormat? = nil) {
		self.threadId = threadId
		self.startFrame = startFrame
		self.levels = levels
		self.format = format
	}
}

public struct DAPStackFrame: Codable, Equatable, Sendable {
	public var id: Int
	public var name: String
	public var source: DAPSource?
	public var line: Int
	public var column: Int
	public var endLine: Int?
	public var endColumn: Int?

	public init(id: Int, name: String, source: DAPSource? = nil, line: Int, column: Int, endLine: Int? = nil, endColumn: Int? = nil) {
		self.id = id
		self.name = name
		self.source = source
		self.line = line
		self.column = column
		self.endLine = endLine
		self.endColumn = endColumn
	}
}

public struct DAPStackTraceResponseBody: Codable, Equatable, Sendable {
	public var stackFrames: [DAPStackFrame]
	public var totalFrames: Int?

	public init(stackFrames: [DAPStackFrame], totalFrames: Int? = nil) {
		self.stackFrames = stackFrames
		self.totalFrames = totalFrames
	}
}

public struct DAPScopesArguments: Codable, Equatable, Sendable {
	public var frameId: Int

	public init(frameId: Int) {
		self.frameId = frameId
	}
}

public struct DAPScope: Codable, Equatable, Sendable {
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

public struct DAPScopesResponseBody: Codable, Equatable, Sendable {
	public var scopes: [DAPScope]

	public init(scopes: [DAPScope]) {
		self.scopes = scopes
	}
}

public struct DAPVariablesArguments: Codable, Equatable, Sendable {
	public var variablesReference: Int
	public var filter: String?
	public var start: Int?
	public var count: Int?
	public var format: DAPValueFormat?

	public init(variablesReference: Int, filter: String? = nil, start: Int? = nil, count: Int? = nil, format: DAPValueFormat? = nil) {
		self.variablesReference = variablesReference
		self.filter = filter
		self.start = start
		self.count = count
		self.format = format
	}
}

public struct DAPVariable: Codable, Equatable, Sendable {
	public var name: String
	public var value: String
	public var type: String?
	public var variablesReference: Int
	public var namedVariables: Int?
	public var indexedVariables: Int?
	public var memoryReference: String?

	public init(name: String, value: String, type: String? = nil, variablesReference: Int = 0, namedVariables: Int? = nil, indexedVariables: Int? = nil, memoryReference: String? = nil) {
		self.name = name
		self.value = value
		self.type = type
		self.variablesReference = variablesReference
		self.namedVariables = namedVariables
		self.indexedVariables = indexedVariables
		self.memoryReference = memoryReference
	}
}

public struct DAPVariablesResponseBody: Codable, Equatable, Sendable {
	public var variables: [DAPVariable]

	public init(variables: [DAPVariable]) {
		self.variables = variables
	}
}

public struct DAPSetVariableArguments: Codable, Equatable, Sendable {
	public var variablesReference: Int
	public var name: String
	public var value: String
	public var format: DAPValueFormat?

	public init(variablesReference: Int, name: String, value: String, format: DAPValueFormat? = nil) {
		self.variablesReference = variablesReference
		self.name = name
		self.value = value
		self.format = format
	}
}

public struct DAPSetVariableResponseBody: Codable, Equatable, Sendable {
	public var value: String
	public var type: String?
	public var variablesReference: Int?
	public var namedVariables: Int?
	public var indexedVariables: Int?
	public var memoryReference: String?
	public var valueLocationReference: Int?

	public init(value: String, type: String? = nil, variablesReference: Int? = nil, namedVariables: Int? = nil, indexedVariables: Int? = nil, memoryReference: String? = nil, valueLocationReference: Int? = nil) {
		self.value = value
		self.type = type
		self.variablesReference = variablesReference
		self.namedVariables = namedVariables
		self.indexedVariables = indexedVariables
		self.memoryReference = memoryReference
		self.valueLocationReference = valueLocationReference
	}
}

public struct DAPVariablePresentationHint: Codable, Equatable, Sendable {
	public var kind: String?
	public var attributes: [String]?
	public var visibility: String?
	public var lazy: Bool?

	public init(kind: String? = nil, attributes: [String]? = nil, visibility: String? = nil, lazy: Bool? = nil) {
		self.kind = kind
		self.attributes = attributes
		self.visibility = visibility
		self.lazy = lazy
	}
}

public struct DAPEvaluateArguments: Codable, Equatable, Sendable {
	public var expression: String
	public var frameId: Int?
	public var line: Int?
	public var column: Int?
	public var source: DAPSource?
	public var context: String?
	public var format: DAPValueFormat?

	public init(expression: String, frameId: Int? = nil, line: Int? = nil, column: Int? = nil, source: DAPSource? = nil, context: String? = nil, format: DAPValueFormat? = nil) {
		self.expression = expression
		self.frameId = frameId
		self.line = line
		self.column = column
		self.source = source
		self.context = context
		self.format = format
	}
}

public struct DAPEvaluateResponseBody: Codable, Equatable, Sendable {
	public var result: String
	public var type: String?
	public var presentationHint: DAPVariablePresentationHint?
	public var variablesReference: Int
	public var namedVariables: Int?
	public var indexedVariables: Int?
	public var memoryReference: String?
	public var valueLocationReference: Int?

	public init(result: String, type: String? = nil, presentationHint: DAPVariablePresentationHint? = nil, variablesReference: Int, namedVariables: Int? = nil, indexedVariables: Int? = nil, memoryReference: String? = nil, valueLocationReference: Int? = nil) {
		self.result = result
		self.type = type
		self.presentationHint = presentationHint
		self.variablesReference = variablesReference
		self.namedVariables = namedVariables
		self.indexedVariables = indexedVariables
		self.memoryReference = memoryReference
		self.valueLocationReference = valueLocationReference
	}
}

public struct DAPContinueArguments: Codable, Equatable, Sendable {
	public var threadId: Int
	public var singleThread: Bool?

	public init(threadId: Int, singleThread: Bool? = nil) {
		self.threadId = threadId
		self.singleThread = singleThread
	}
}

public struct DAPContinueResponseBody: Codable, Equatable, Sendable {
	public var allThreadsContinued: Bool?

	public init(allThreadsContinued: Bool? = nil) {
		self.allThreadsContinued = allThreadsContinued
	}
}

public enum DAPSteppingGranularity {
	public static let statement = "statement"
	public static let line = "line"
	public static let instruction = "instruction"
}

public struct DAPNextArguments: Codable, Equatable, Sendable {
	public var threadId: Int
	public var singleThread: Bool?
	public var granularity: String?

	public init(threadId: Int, singleThread: Bool? = nil, granularity: String? = nil) {
		self.threadId = threadId
		self.singleThread = singleThread
		self.granularity = granularity
	}
}

public struct DAPStepInArguments: Codable, Equatable, Sendable {
	public var threadId: Int
	public var singleThread: Bool?
	public var targetId: Int?
	public var granularity: String?

	public init(threadId: Int, singleThread: Bool? = nil, targetId: Int? = nil, granularity: String? = nil) {
		self.threadId = threadId
		self.singleThread = singleThread
		self.targetId = targetId
		self.granularity = granularity
	}
}

public struct DAPStepOutArguments: Codable, Equatable, Sendable {
	public var threadId: Int
	public var singleThread: Bool?
	public var granularity: String?

	public init(threadId: Int, singleThread: Bool? = nil, granularity: String? = nil) {
		self.threadId = threadId
		self.singleThread = singleThread
		self.granularity = granularity
	}
}

public struct DAPPauseArguments: Codable, Equatable, Sendable {
	public var threadId: Int

	public init(threadId: Int) {
		self.threadId = threadId
	}
}

public struct DAPReverseContinueArguments: Codable, Equatable, Sendable {
	public var threadId: Int
	public var singleThread: Bool?

	public init(threadId: Int, singleThread: Bool? = nil) {
		self.threadId = threadId
		self.singleThread = singleThread
	}
}

public struct DAPStoppedEventBody: Codable, Equatable, Sendable {
	public var reason: String
	public var description: String?
	public var threadId: Int?
	public var preserveFocusHint: Bool?
	public var text: String?
	public var allThreadsStopped: Bool?
	public var hitBreakpointIds: [Int]?

	public init(reason: String, description: String? = nil, threadId: Int? = nil, preserveFocusHint: Bool? = nil, text: String? = nil, allThreadsStopped: Bool? = nil, hitBreakpointIds: [Int]? = nil) {
		self.reason = reason
		self.description = description
		self.threadId = threadId
		self.preserveFocusHint = preserveFocusHint
		self.text = text
		self.allThreadsStopped = allThreadsStopped
		self.hitBreakpointIds = hitBreakpointIds
	}
}

public struct DAPContinuedEventBody: Codable, Equatable, Sendable {
	public var threadId: Int
	public var allThreadsContinued: Bool?

	public init(threadId: Int, allThreadsContinued: Bool? = nil) {
		self.threadId = threadId
		self.allThreadsContinued = allThreadsContinued
	}
}

public struct DAPExitedEventBody: Codable, Equatable, Sendable {
	public var exitCode: Int

	public init(exitCode: Int) {
		self.exitCode = exitCode
	}
}

public struct DAPTerminatedEventBody: Codable, Equatable, Sendable {
	public var restart: DAPAny?

	public init(restart: DAPAny? = nil) {
		self.restart = restart
	}
}

public struct DAPThreadEventBody: Codable, Equatable, Sendable {
	public var reason: String
	public var threadId: Int

	public init(reason: String, threadId: Int) {
		self.reason = reason
		self.threadId = threadId
	}
}

public struct DAPOutputEventBody: Codable, Equatable, Sendable {
	public var category: String?
	public var output: String
	public var group: String?
	public var variablesReference: Int?
	public var source: DAPSource?
	public var line: Int?
	public var column: Int?
	public var data: DAPAny?
	public var locationReference: Int?

	public init(category: String? = nil, output: String, group: String? = nil, variablesReference: Int? = nil, source: DAPSource? = nil, line: Int? = nil, column: Int? = nil, data: DAPAny? = nil, locationReference: Int? = nil) {
		self.category = category
		self.output = output
		self.group = group
		self.variablesReference = variablesReference
		self.source = source
		self.line = line
		self.column = column
		self.data = data
		self.locationReference = locationReference
	}
}

public enum DAPOutputCategory {
	public static let console = "console"
	public static let stdout = "stdout"
	public static let stderr = "stderr"
	public static let important = "important"
	public static let telemetry = "telemetry"
}

public struct DAPBreakpointEventBody: Codable, Equatable, Sendable {
	public var reason: String
	public var breakpoint: DAPBreakpoint

	public init(reason: String, breakpoint: DAPBreakpoint) {
		self.reason = reason
		self.breakpoint = breakpoint
	}
}

public struct DAPModule: Codable, Equatable, Sendable {
	public var id: DAPAny
	public var name: String
	public var path: String?
	public var isOptimized: Bool?
	public var isUserCode: Bool?
	public var version: String?
	public var symbolStatus: String?
	public var symbolFilePath: String?
	public var dateTimeStamp: String?
	public var addressRange: String?

	public init(id: DAPAny, name: String, path: String? = nil, isOptimized: Bool? = nil, isUserCode: Bool? = nil, version: String? = nil, symbolStatus: String? = nil, symbolFilePath: String? = nil, dateTimeStamp: String? = nil, addressRange: String? = nil) {
		self.id = id
		self.name = name
		self.path = path
		self.isOptimized = isOptimized
		self.isUserCode = isUserCode
		self.version = version
		self.symbolStatus = symbolStatus
		self.symbolFilePath = symbolFilePath
		self.dateTimeStamp = dateTimeStamp
		self.addressRange = addressRange
	}
}

public struct DAPModuleEventBody: Codable, Equatable, Sendable {
	public var reason: String
	public var module: DAPModule

	public init(reason: String, module: DAPModule) {
		self.reason = reason
		self.module = module
	}
}

public struct DAPLoadedSourceEventBody: Codable, Equatable, Sendable {
	public var reason: String
	public var source: DAPSource

	public init(reason: String, source: DAPSource) {
		self.reason = reason
		self.source = source
	}
}

public struct DAPProcessEventBody: Codable, Equatable, Sendable {
	public var name: String
	public var systemProcessId: Int?
	public var isLocalProcess: Bool?
	public var startMethod: String?
	public var pointerSize: Int?

	public init(name: String, systemProcessId: Int? = nil, isLocalProcess: Bool? = nil, startMethod: String? = nil, pointerSize: Int? = nil) {
		self.name = name
		self.systemProcessId = systemProcessId
		self.isLocalProcess = isLocalProcess
		self.startMethod = startMethod
		self.pointerSize = pointerSize
	}
}

public struct DAPCapabilitiesEventBody: Codable, Equatable, Sendable {
	public var capabilities: DAPCapabilities

	public init(capabilities: DAPCapabilities) {
		self.capabilities = capabilities
	}
}

public enum DAPInvalidatedArea {
	public static let all = "all"
	public static let stacks = "stacks"
	public static let threads = "threads"
	public static let variables = "variables"
}

public struct DAPInvalidatedEventBody: Codable, Equatable, Sendable {
	public var areas: [String]?
	public var threadId: Int?
	public var stackFrameId: Int?

	public init(areas: [String]? = nil, threadId: Int? = nil, stackFrameId: Int? = nil) {
		self.areas = areas
		self.threadId = threadId
		self.stackFrameId = stackFrameId
	}
}

public enum DAPTypedEventError: Error, Equatable, Sendable {
	case missingBody(String)
}

public enum DAPTypedEvent: Equatable, Sendable {
	case initialized
	case stopped(DAPStoppedEventBody)
	case continued(DAPContinuedEventBody)
	case exited(DAPExitedEventBody)
	case terminated(DAPTerminatedEventBody)
	case thread(DAPThreadEventBody)
	case output(DAPOutputEventBody)
	case breakpoint(DAPBreakpointEventBody)
	case module(DAPModuleEventBody)
	case loadedSource(DAPLoadedSourceEventBody)
	case process(DAPProcessEventBody)
	case capabilities(DAPCapabilitiesEventBody)
	case invalidated(DAPInvalidatedEventBody)
	case unknown(DAPEventMessage)

	public init(message: DAPEventMessage) throws {
		switch message.event {
		case DAPEvent.initialized:
			self = .initialized
		case DAPEvent.stopped:
			self = .stopped(try Self.body(message, as: DAPStoppedEventBody.self))
		case DAPEvent.continued:
			self = .continued(try Self.body(message, as: DAPContinuedEventBody.self))
		case DAPEvent.exited:
			self = .exited(try Self.body(message, as: DAPExitedEventBody.self))
		case DAPEvent.terminated:
			if message.body == nil {
				self = .terminated(DAPTerminatedEventBody())
			} else {
				self = .terminated(try Self.body(message, as: DAPTerminatedEventBody.self))
			}
		case DAPEvent.thread:
			self = .thread(try Self.body(message, as: DAPThreadEventBody.self))
		case DAPEvent.output:
			self = .output(try Self.body(message, as: DAPOutputEventBody.self))
		case DAPEvent.breakpoint:
			self = .breakpoint(try Self.body(message, as: DAPBreakpointEventBody.self))
		case DAPEvent.module:
			self = .module(try Self.body(message, as: DAPModuleEventBody.self))
		case DAPEvent.loadedSource:
			self = .loadedSource(try Self.body(message, as: DAPLoadedSourceEventBody.self))
		case DAPEvent.process:
			self = .process(try Self.body(message, as: DAPProcessEventBody.self))
		case DAPEvent.capabilities:
			self = .capabilities(try Self.body(message, as: DAPCapabilitiesEventBody.self))
		case DAPEvent.invalidated:
			self = .invalidated(try Self.body(message, as: DAPInvalidatedEventBody.self))
		default:
			self = .unknown(message)
		}
	}

	private static func body<Value: Decodable>(_ message: DAPEventMessage, as type: Value.Type) throws -> Value {
		guard let body = message.body else {
			throw DAPTypedEventError.missingBody(message.event)
		}
		return try JSONDecoder().decode(type, from: JSONEncoder().encode(body))
	}
}

public extension DAPEventMessage {
	func typed() throws -> DAPTypedEvent {
		try DAPTypedEvent(message: self)
	}
}
