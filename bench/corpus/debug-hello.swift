func makeValue(_ value: Int) -> Int {
	let stepped = value + 1 // BREAKPOINT
	let watched = stepped * 2
	print("watched=\(watched)")
	return watched
}

let result = makeValue(41)
print("result=\(result)")
