package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class GitPermalinkTest {
    @Test
    void createsImmutableLinksForSupportedRemoteForms() {
        assertEquals("https://github.com/acme/shed/blob/abcdef1/src/Main%20File.java#L7",
            GitPermalink.create("git@github.com:acme/shed.git", "abcdef1", "src/Main File.java", 7));
        assertEquals("https://gitlab.com/acme/shed/-/blob/abcdef1/src/Main.java#L1",
            GitPermalink.create("https://gitlab.com/acme/shed.git", "abcdef1", "src/Main.java", 0));
        assertEquals("https://bitbucket.org/acme/shed/src/abcdef1/src/Main.java#Main.java-3",
            GitPermalink.create("ssh://git@bitbucket.org/acme/shed.git", "abcdef1", "src/Main.java", 3));
    }

    @Test
    void rejectsUnsupportedRemotesAndUnsafePaths() {
        assertThrows(IllegalArgumentException.class, () -> GitPermalink.create("https://example.com/a/b.git", "abcdef1", "Main.java", 1));
        assertThrows(IllegalArgumentException.class, () -> GitPermalink.create("git@github.com:acme/shed.git", "abcdef1", "../Main.java", 1));
    }

    @Test
    void createsVerifiedGitHubAndGitLabLineRanges() {
        assertEquals("https://github.com/acme/shed/blob/abcdef1/src/Main.java#L3-L8",
            GitPermalink.create("git@github.com:acme/shed.git", "abcdef1", "src/Main.java", 3, 8));
        assertEquals("https://gitlab.com/acme/shed/-/blob/abcdef1/src/Main.java#L3-8",
            GitPermalink.create("https://gitlab.com/acme/shed.git", "abcdef1", "src/Main.java", 3, 8));
        assertThrows(IllegalArgumentException.class, () -> GitPermalink.create("https://bitbucket.org/acme/shed.git", "abcdef1", "src/Main.java", 3, 8));
    }
}
