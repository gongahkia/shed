@testable import ItsyApp
import Testing

@Test func terminalEnvironmentForwardsOnlyAllowlistedKeys() {
	let environment = ItsyTerminalEnvironment.build(
		from: [
			"AWS_SECRET_ACCESS_KEY": "secret",
			"GITHUB_TOKEN": "token",
			"HOME": "/Users/tester",
			"ITSY_EDITOR_STORAGE": "rope",
			"LANG": "en_US.UTF-8",
			"PATH": "/custom/bin",
			"SHELL": "/bin/bash",
			"SSH_AUTH_SOCK": "/tmp/ssh.sock",
			"TMPDIR": "/tmp/",
			"USER": "tester",
		],
		shellPath: "/bin/zsh"
	)

	#expect(environment["HOME"] == "/Users/tester")
	#expect(environment["PATH"] == "/custom/bin")
	#expect(environment["SSH_AUTH_SOCK"] == "/tmp/ssh.sock")
	#expect(environment["AWS_SECRET_ACCESS_KEY"] == nil)
	#expect(environment["GITHUB_TOKEN"] == nil)
	#expect(environment["ITSY_EDITOR_STORAGE"] == nil)
}

@Test func terminalEnvironmentInjectsTerminalDefaults() {
	let environment = ItsyTerminalEnvironment.build(
		from: [
			"COLORTERM": "no",
			"TERM": "dumb",
		],
		shellPath: "/bin/zsh"
	)

	#expect(environment["PATH"] == "/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin")
	#expect(environment["SHELL"] == "/bin/zsh")
	#expect(environment["INSIDE_ITSY_TERMINAL"] == "1")
	#expect(environment["TERM"] == "xterm-256color")
	#expect(environment["TERM_PROGRAM"] == "Itsy")
	#expect(environment["COLORTERM"] == "truecolor")
	#expect(environment["LC_CTYPE"] == "UTF-8")
}

@Test func terminalEnvironmentIgnoresEmptyLocaleFallback() {
	let environment = ItsyTerminalEnvironment.build(
		from: ["LANG": ""],
		shellPath: "/bin/zsh"
	)

	#expect(environment["LC_CTYPE"] == "UTF-8")
}
