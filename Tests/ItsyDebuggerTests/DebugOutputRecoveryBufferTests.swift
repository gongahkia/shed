import ItsyDAP
import ItsyDebugger
import Testing

@Test func debugOutputRecoveryBufferRetainsRecentOutputWithinByteBudget() async {
	let buffer = DebugOutputRecoveryBuffer(maximumBytes: 5)
	await buffer.append(sequence: 1, body: DAPOutputEventBody(output: "one"))
	await buffer.append(sequence: 2, body: DAPOutputEventBody(category: DAPOutputCategory.stderr, output: "two"))
	await buffer.append(sequence: 3, body: DAPOutputEventBody(output: "x"))
	await buffer.append(sequence: 4, body: DAPOutputEventBody(output: "oversized"))

	#expect(await buffer.snapshot() == [
		DebugOutputRecoveryEntry(sequence: 2, body: DAPOutputEventBody(category: DAPOutputCategory.stderr, output: "two")),
		DebugOutputRecoveryEntry(sequence: 3, body: DAPOutputEventBody(output: "x")),
	])
}
