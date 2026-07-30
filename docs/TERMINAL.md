# Terminal Conformance Fixture

`TerminalConformanceFixtureTest` feeds deterministic characters into Jediterm's emulator without launching a shell. It covers plain output, ANSI SGR, newline output, Up-arrow and Enter input codes, and an unsupported private CSI sequence. The fixture requires surrounding output to remain intact and escape control bytes not to leak into terminal text.

Shed retains Jediterm/PTy4J behavior for terminal emulation. The fixture is a regression boundary, not a claim of complete xterm-sequence support.
