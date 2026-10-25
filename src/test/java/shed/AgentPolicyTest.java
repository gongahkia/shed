package shed;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AgentPolicyTest {
    @Test void readOnlyDoesNotAuthorizeProposalsWritesProcessesOrNetwork() {
        AgentPolicy policy = AgentPolicy.readOnly();
        policy.checkPath(Path.of("README.md"), AgentPolicy.Capability.READ);
        for (var capability : AgentPolicy.Capability.values()) {
            if (capability != AgentPolicy.Capability.READ)
                assertEquals(WorkspaceApiException.Code.PERMISSION_DENIED,
                    assertThrows(WorkspaceApiException.class, () -> policy.require(capability)).code());
        }
    }

    @Test void backgroundOnlyIsIndependentFromGrantedCapabilities() {
        AgentPolicy policy = new AgentPolicy(EnumSet.allOf(AgentPolicy.Capability.class), List.of(), List.of(), true);
        policy.require(AgentPolicy.Capability.RUN_HEADLESS);
        policy.require(AgentPolicy.Capability.APPLY);
        for (var capability : Set.of(AgentPolicy.Capability.OPEN_UI, AgentPolicy.Capability.NOTIFY, AgentPolicy.Capability.RUN_INTERACTIVE))
            assertEquals(WorkspaceApiException.Code.DEFERRED_REQUIRES_FOREGROUND,
                assertThrows(WorkspaceApiException.class, () -> policy.require(capability)).code());
    }

    @Test void protectedPathsAndExclusionsCoverRootNestedAndAdministrativeFiles() {
        AgentPolicy policy = new AgentPolicy(EnumSet.allOf(AgentPolicy.Capability.class),
            List.of("**/README.md"), List.of("secrets/**", ".env"), false);
        for (String path : List.of("README.md", "sub/README.md", ".git/config", "sub/.git/HEAD"))
            assertEquals(WorkspaceApiException.Code.PROTECTED_PATH,
                assertThrows(WorkspaceApiException.class, () -> policy.checkPath(Path.of(path), AgentPolicy.Capability.APPLY)).code());
        for (String path : List.of("secrets/token.txt", ".env"))
            assertEquals(WorkspaceApiException.Code.EXCLUDED_PATH,
                assertThrows(WorkspaceApiException.class, () -> policy.checkPath(Path.of(path), AgentPolicy.Capability.READ)).code());
        assertEquals(WorkspaceApiException.Code.OUTSIDE_WORKSPACE,
            assertThrows(WorkspaceApiException.class, () -> policy.checkPath(Path.of("../outside"), AgentPolicy.Capability.READ)).code());
    }

    @Test void policyIsImmutableAndRejectsAmbiguousPatterns() {
        var capabilities = EnumSet.of(AgentPolicy.Capability.READ);
        var policy = new AgentPolicy(capabilities, List.of(), List.of(), false);
        capabilities.add(AgentPolicy.Capability.APPLY);
        assertFalse(policy.capabilities().contains(AgentPolicy.Capability.APPLY));
        for (String pattern : List.of("/absolute", "../escape", "a/../b", "C:/windows", "a\\b", ""))
            assertThrows(IllegalArgumentException.class, () -> new AgentPolicy(Set.of(), List.of(pattern), List.of(), false));
    }

    @Test void protectedAndExcludedDirectoryMatchesAlsoGuardTheirDescendants() {
        var policy = new AgentPolicy(EnumSet.allOf(AgentPolicy.Capability.class), List.of("generated"), List.of("secrets"), false);
        assertEquals(WorkspaceApiException.Code.EXCLUDED_PATH, assertThrows(WorkspaceApiException.class,
            () -> policy.checkPath(Path.of("secrets/nested/token"), AgentPolicy.Capability.READ)).code());
        assertEquals(WorkspaceApiException.Code.PROTECTED_PATH, assertThrows(WorkspaceApiException.class,
            () -> policy.checkPath(Path.of("generated/nested/source"), AgentPolicy.Capability.APPLY)).code());
        policy.checkPath(Path.of("generated/nested/source"), AgentPolicy.Capability.READ);
        policy.checkPath(Path.of("other/secrets/token"), AgentPolicy.Capability.READ);
    }
}
