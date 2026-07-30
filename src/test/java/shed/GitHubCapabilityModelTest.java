package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public class GitHubCapabilityModelTest {
    private static final CommandResult OK = new CommandResult(0, "ok", "");

    @Test
    void reportsReadyButDisabledWithoutAnyMutationState() {
        GitHubCapabilityModel.Report report = GitHubCapabilityModel.inspect(false, new CommandResult(0, "gh version 2.96.0", ""), OK,
            new CommandResult(0, "git@github.com:owner/repo.git\n", ""), OK, OK, OK);

        assertTrue(report.ready());
        assertFalse(report.enabled());
        assertEquals("owner/repo", report.repository());
        assertTrue(report.format().contains("local gh and Git commands only"));
    }

    @Test
    void givesExactRemediationForUnavailableAuthAndRepositoryStates() {
        GitHubCapabilityModel.Report unavailable = GitHubCapabilityModel.inspect(false, new CommandResult(-1, "", "not found"), OK, OK, OK, OK, OK);
        GitHubCapabilityModel.Report unauthenticated = GitHubCapabilityModel.inspect(false, new CommandResult(0, "gh version 2.1.0", ""),
            new CommandResult(1, "", "not logged in"), OK, OK, OK, OK);
        GitHubCapabilityModel.Report missingRepository = GitHubCapabilityModel.inspect(false, new CommandResult(0, "gh version 2.1.0", ""), OK,
            new CommandResult(0, "https://example.com/owner/repo.git", ""), OK, OK, OK);

        assertTrue(unavailable.remediation().contains("Install GitHub CLI"));
        assertTrue(unauthenticated.remediation().contains("gh auth login"));
        assertTrue(missingRepository.remediation().contains("git remote add origin"));
    }

    @Test
    void rejectsUnsupportedVersionAndMissingRequiredSubcommands() {
        GitHubCapabilityModel.Report old = GitHubCapabilityModel.inspect(false, new CommandResult(0, "gh version 1.14.0", ""), OK, OK, OK, OK, OK);
        GitHubCapabilityModel.Report missing = GitHubCapabilityModel.inspect(false, new CommandResult(0, "gh version 2.1.0", ""), OK,
            new CommandResult(0, "https://github.com/owner/repo.git", ""), OK, new CommandResult(1, "", "unsupported"), OK);

        assertFalse(old.supported());
        assertTrue(old.remediation().contains("2.0.0"));
        assertEquals("owner/repo", missing.repository());
        assertTrue(missing.missing().contains("gh pr view"));
    }
}
