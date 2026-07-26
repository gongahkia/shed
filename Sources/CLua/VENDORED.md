# Vendored Lua runtime

- Version: Lua 5.4.8
- Source: https://www.lua.org/ftp/lua-5.4.8.tar.gz
- SHA-256: `4f18ddae154e793e46eeab727c59ef1c0c0c2b744e7b94219710d76f530629ae`
- License: Lua license, included in `doc/readme.html`.

`Package.swift` compiles the runtime and standard-library sources only; `lua.c` and `luac.c` are not linked.
