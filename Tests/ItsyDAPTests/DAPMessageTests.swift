import Foundation
import ItsyDAP
import Testing

@Test func dapRequestEncodesInitializeArguments() throws {
	let arguments = DAPInitializeRequestArguments(
		clientID: "itsy",
		clientName: "Itsy",
		adapterID: "lldb",
		linesStartAt1: true,
		columnsStartAt1: true,
		supportsVariableType: true
	)
	let message = DAPMessage.request(DAPRequestMessage(
		seq: 1,
		command: DAPCommand.initialize,
		arguments: try DAPAny(encoding: arguments)
	))

	let payload = try JSONEncoder().encode(message)
	let decoded = try JSONDecoder().decode(DAPMessage.self, from: payload)

	#expect(decoded == message)
}

@Test func dapDecodesResponseWithStackFrames() throws {
	let data = Data(#"""
	{
	  "seq": 4,
	  "type": "response",
	  "request_seq": 3,
	  "success": true,
	  "command": "stackTrace",
	  "body": {
	    "stackFrames": [
	      {
	        "id": 9,
	        "name": "main",
	        "source": { "name": "main.swift", "path": "/tmp/main.swift" },
	        "line": 12,
	        "column": 5
	      }
	    ],
	    "totalFrames": 1
	  }
	}
	"""#.utf8)

	let message = try JSONDecoder().decode(DAPMessage.self, from: data)
	let response = try #require(responseMessage(from: message))
	let bodyValue = try #require(response.body)
	let body = try JSONDecoder().decode(DAPStackTraceResponseBody.self, from: JSONEncoder().encode(bodyValue))

	#expect(response.requestSeq == 3)
	#expect(response.command == DAPCommand.stackTrace)
	#expect(body.stackFrames == [
		DAPStackFrame(id: 9, name: "main", source: DAPSource(name: "main.swift", path: "/tmp/main.swift"), line: 12, column: 5),
	])
	#expect(body.totalFrames == 1)
}

@Test func dapDecodesStoppedEventBody() throws {
	let data = Data(#"""
	{
	  "seq": 7,
	  "type": "event",
	  "event": "stopped",
	  "body": {
	    "reason": "breakpoint",
	    "threadId": 2,
	    "allThreadsStopped": true
	  }
	}
	"""#.utf8)

	let message = try JSONDecoder().decode(DAPMessage.self, from: data)
	let event = try #require(eventMessage(from: message))
	let bodyValue = try #require(event.body)
	let body = try JSONDecoder().decode(DAPStoppedEventBody.self, from: JSONEncoder().encode(bodyValue))

	#expect(event.event == DAPEvent.stopped)
	#expect(body == DAPStoppedEventBody(reason: "breakpoint", threadId: 2, allThreadsStopped: true))
}

@Test func dapBreakpointAndVariableTypesRoundTrip() throws {
	let breakpoints = DAPSetBreakpointsArguments(
		source: DAPSource(path: "/tmp/main.swift"),
		breakpoints: [DAPSourceBreakpoint(line: 10, column: 2, condition: "value > 0")]
	)
	let scopes = DAPScopesResponseBody(scopes: [DAPScope(name: "Locals", variablesReference: 42, expensive: false)])
	let variables = DAPVariablesResponseBody(variables: [DAPVariable(name: "value", value: "1", type: "Int")])

	#expect(try JSONDecoder().decode(DAPSetBreakpointsArguments.self, from: JSONEncoder().encode(breakpoints)) == breakpoints)
	#expect(try JSONDecoder().decode(DAPScopesResponseBody.self, from: JSONEncoder().encode(scopes)) == scopes)
	#expect(try JSONDecoder().decode(DAPVariablesResponseBody.self, from: JSONEncoder().encode(variables)) == variables)
}

@Test func dapCommandConstantsCoverDebuggerFlow() {
	#expect(DAPCommand.initialize == "initialize")
	#expect(DAPCommand.launch == "launch")
	#expect(DAPCommand.attach == "attach")
	#expect(DAPCommand.configurationDone == "configurationDone")
	#expect(DAPCommand.disconnect == "disconnect")
	#expect(DAPCommand.terminate == "terminate")
	#expect(DAPCommand.threads == "threads")
	#expect(DAPCommand.stackTrace == "stackTrace")
	#expect(DAPCommand.scopes == "scopes")
	#expect(DAPCommand.variables == "variables")
	#expect(DAPCommand.evaluate == "evaluate")
	#expect(DAPCommand.setBreakpoints == "setBreakpoints")
	#expect(DAPCommand.setFunctionBreakpoints == "setFunctionBreakpoints")
	#expect(DAPCommand.setExceptionBreakpoints == "setExceptionBreakpoints")
	#expect(DAPCommand.setDataBreakpoints == "setDataBreakpoints")
	#expect(DAPCommand.continueExecution == "continue")
	#expect(DAPCommand.next == "next")
	#expect(DAPCommand.stepIn == "stepIn")
	#expect(DAPCommand.stepOut == "stepOut")
	#expect(DAPCommand.pause == "pause")
	#expect(DAPCommand.reverseContinue == "reverseContinue")
	#expect(DAPCommand.restart == "restart")
}

@Test func dapLifecycleRequestTypesRoundTrip() throws {
	let launch = DAPLaunchRequestArguments(
		noDebug: false,
		restartData: .object(["session": .int(1)]),
		program: "/tmp/app",
		args: ["--flag"],
		cwd: "/tmp",
		env: ["A": "B"],
		stopOnEntry: true
	)
	let launchValue = try DAPAny(encoding: launch)
	#expect(launchValue == .object([
		"__restart": .object(["session": .int(1)]),
		"args": .array([.string("--flag")]),
		"cwd": .string("/tmp"),
		"env": .object(["A": .string("B")]),
		"noDebug": .bool(false),
		"program": .string("/tmp/app"),
		"stopOnEntry": .bool(true),
	]))

	try expectRoundTrip(launch)
	try expectRoundTrip(DAPAttachRequestArguments(restartData: .string("r"), pid: 42, program: "/tmp/app"))
	try expectRoundTrip(DAPRestartArguments(arguments: launchValue))
	try expectRoundTrip(DAPDisconnectArguments(restart: true, terminateDebuggee: true, suspendDebuggee: false))
	try expectRoundTrip(DAPTerminateArguments(restart: false))
	try expectRoundTrip(DAPConfigurationDoneArguments())
	try expectRoundTrip(DAPInitializeResponseBody(supportsRestartRequest: true, supportsValueFormattingOptions: true, supportTerminateDebuggee: true))
}

@Test func dapAdvancedBreakpointTypesRoundTrip() throws {
	let functionArgs = DAPSetFunctionBreakpointsArguments(breakpoints: [
		DAPFunctionBreakpoint(name: "main", condition: "argc > 1", hitCondition: "2"),
	])
	let exceptionArgs = DAPSetExceptionBreakpointsArguments(
		filters: ["swift"],
		filterOptions: [DAPExceptionFilterOptions(filterId: "objc", condition: "enabled")],
		exceptionOptions: [
			DAPExceptionOptions(
				path: [DAPExceptionPathSegment(negate: false, names: ["NSException"])],
				breakMode: "always"
			),
		]
	)
	let dataArgs = DAPSetDataBreakpointsArguments(breakpoints: [
		DAPDataBreakpoint(dataId: "watch:1", accessType: "readWrite", condition: "value > 0", hitCondition: "3"),
	])
	let response = DAPSetBreakpointsResponseBody(breakpoints: [DAPBreakpoint(id: 1, verified: true)])

	try expectRoundTrip(functionArgs)
	try expectRoundTrip(DAPSetFunctionBreakpointsResponseBody(breakpoints: response.breakpoints))
	try expectRoundTrip(exceptionArgs)
	try expectRoundTrip(DAPSetExceptionBreakpointsResponseBody(breakpoints: response.breakpoints))
	try expectRoundTrip(dataArgs)
	try expectRoundTrip(DAPSetDataBreakpointsResponseBody(breakpoints: response.breakpoints))
}

@Test func dapExecutionControlAndEvaluateTypesRoundTrip() throws {
	try expectRoundTrip(DAPStackTraceArguments(threadId: 1, startFrame: 2, levels: 10, format: DAPStackFrameFormat(hex: true, parameters: true, parameterNames: true, line: true)))
	try expectRoundTrip(DAPVariablesArguments(variablesReference: 99, filter: "named", start: 0, count: 20, format: DAPValueFormat(hex: true)))
	try expectRoundTrip(DAPEvaluateArguments(expression: "value + 1", frameId: 7, line: 12, column: 3, source: DAPSource(path: "/tmp/main.swift"), context: "repl", format: DAPValueFormat(hex: false)))
	try expectRoundTrip(DAPEvaluateResponseBody(
		result: "42",
		type: "Int",
		presentationHint: DAPVariablePresentationHint(kind: "property", attributes: ["readOnly"], visibility: "public", lazy: false),
		variablesReference: 0,
		namedVariables: 0,
		indexedVariables: 0,
		memoryReference: "0x2a",
		valueLocationReference: 5
	))
	try expectRoundTrip(DAPContinueArguments(threadId: 1, singleThread: true))
	try expectRoundTrip(DAPNextArguments(threadId: 1, singleThread: true, granularity: DAPSteppingGranularity.line))
	try expectRoundTrip(DAPStepInArguments(threadId: 1, singleThread: false, targetId: 9, granularity: DAPSteppingGranularity.statement))
	try expectRoundTrip(DAPStepOutArguments(threadId: 1, singleThread: true, granularity: DAPSteppingGranularity.instruction))
	try expectRoundTrip(DAPPauseArguments(threadId: 1))
	try expectRoundTrip(DAPReverseContinueArguments(threadId: 1, singleThread: true))
}

@Test func dapErrorResponseBodyDecodesMessage() throws {
	let body = DAPErrorResponseBody(error: DAPErrorMessage(
		id: 1001,
		format: "launch failed: {reason}",
		variables: ["reason": "missing program"],
		showUser: true
	))
	let message = DAPMessage.response(DAPResponseMessage(
		seq: 2,
		requestSeq: 1,
		success: false,
		command: DAPCommand.launch,
		message: "launch failed",
		body: try DAPAny(encoding: body)
	))

	let decoded = try JSONDecoder().decode(DAPMessage.self, from: JSONEncoder().encode(message))
	let response = try #require(responseMessage(from: decoded))
	let decodedBody = try JSONDecoder().decode(DAPErrorResponseBody.self, from: JSONEncoder().encode(try #require(response.body)))

	#expect(response.success == false)
	#expect(response.requestSeq == 1)
	#expect(decodedBody == body)
}

private func responseMessage(from message: DAPMessage) -> DAPResponseMessage? {
	if case let .response(response) = message {
		return response
	}
	return nil
}

private func eventMessage(from message: DAPMessage) -> DAPEventMessage? {
	if case let .event(event) = message {
		return event
	}
	return nil
}

private func expectRoundTrip<Value: Codable & Equatable>(_ value: Value) throws {
	let decoded = try JSONDecoder().decode(Value.self, from: JSONEncoder().encode(value))
	#expect(decoded == value)
}
