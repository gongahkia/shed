import ItsyEditor
import Testing

@Test func selectionSetMergesOverlaps() {
	var set = SelectionSet(
		primary: Selection(anchor: 10, head: 4),
		secondaries: [
			Selection(anchor: 6, head: 12),
			Selection(anchor: 20, head: 21),
		]
	)
	set.merge()
	#expect(set.primary == Selection(anchor: 4, head: 12))
	#expect(set.secondaries == [Selection(anchor: 20, head: 21)])
}

@Test func selectionSetMapsThroughEdit() {
	let set = SelectionSet(
		primary: Selection(anchor: 2, head: 4),
		secondaries: [Selection(anchor: 10, head: 12)]
	)
	let mapped = set.map(through: Edit(range: 3 ..< 8, oldText: "cdefg", newText: "XYZ"))
	#expect(mapped.primary == Selection(anchor: 2, head: 6))
	#expect(mapped.secondaries == [Selection(anchor: 8, head: 10)])
}
