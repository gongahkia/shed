import CLibgit2
import Testing

@Test func clibgit2ReportsVendoredVersion() {
	var major: Int32 = 0
	var minor: Int32 = 0
	var revision: Int32 = 0

	git_libgit2_version(&major, &minor, &revision)

	#expect(major == 1)
	#expect(minor == 9)
	#expect(revision == 4)
}
