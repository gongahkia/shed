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
