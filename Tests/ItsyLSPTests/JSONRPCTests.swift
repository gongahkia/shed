import Foundation
import ItsyLSP
import Testing

@Test func jsonRPCEncodesRequestWithObjectParams() throws {
	let message = JSONRPCMessage.request(JSONRPCRequestMessage(
		id: .int(1),
		method: LSPMethod.initialize,
		params: .object([
			"processId": .int(123),
			"capabilities": .object([:]),
		])
	))

	let data = try JSONEncoder().encode(message)
	let object = try #require(try JSONSerialization.jsonObject(with: data) as? [String: Any])

	#expect(object["jsonrpc"] as? String == "2.0")
	#expect(object["method"] as? String == "initialize")
	#expect(object["id"] as? Int == 1)
	let params = try #require(object["params"] as? [String: Any])
	#expect(params["processId"] as? Int == 123)
}

@Test func jsonRPCDecodesNotificationWithoutID() throws {
	let data = Data(#"{"jsonrpc":"2.0","method":"initialized","params":{}}"#.utf8)

	let message = try JSONDecoder().decode(JSONRPCMessage.self, from: data)

	#expect(message == .notification(JSONRPCNotificationMessage(method: LSPMethod.initialized, params: .object([:]))))
}

@Test func jsonRPCDecodesErrorResponse() throws {
	let data = Data(#"{"jsonrpc":"2.0","id":"abc","error":{"code":-32601,"message":"Method not found"}}"#.utf8)

	let message = try JSONDecoder().decode(JSONRPCMessage.self, from: data)

	#expect(message == .response(JSONRPCResponseMessage(
		id: .string("abc"),
		error: JSONRPCError(code: JSONRPCErrorCode.methodNotFound, message: "Method not found")
	)))
}

@Test func jsonRPCRejectsWrongVersion() {
	let data = Data(#"{"jsonrpc":"1.0","id":1,"method":"initialize"}"#.utf8)

	#expect(throws: DecodingError.self) {
		_ = try JSONDecoder().decode(JSONRPCMessage.self, from: data)
	}
}
