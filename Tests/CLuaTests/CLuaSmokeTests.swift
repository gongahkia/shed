import CLua
import Testing

@Test func cluaRunsStandardLibraryScriptAndRecoversFromSyntaxFailure() {
	guard let state = luaL_newstate() else {
		Issue.record("expected Lua state")
		return
	}
	defer { lua_close(state) }
	luaL_openlibs(state)
	#expect(lua_version(state) == 504)

	let invalid = "local ="
	let invalidStatus = invalid.withCString { source in
		luaL_loadbufferx(state, source, invalid.utf8.count, "invalid", nil)
	}
	#expect(invalidStatus != 0)
	lua_settop(state, 0)

	let source = "return table.concat({'Lua', '5.4'}, ' ')"
	let loadStatus = source.withCString { script in
		luaL_loadbufferx(state, script, source.utf8.count, "smoke", nil)
	}
	#expect(loadStatus == 0)
	#expect(lua_pcallk(state, 0, 1, 0, 0, nil) == 0)
	#expect(String(cString: lua_tolstring(state, -1, nil)) == "Lua 5.4")
}
