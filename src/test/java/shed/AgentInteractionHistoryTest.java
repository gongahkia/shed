package shed;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentInteractionHistoryTest {
    @TempDir Path temporary;
    @Test void boundsAndSeparatesResultsWithoutLosingProposalIdentity() throws Exception {
        Path one = Files.createDirectory(temporary.resolve("one")), two = Files.createDirectory(temporary.resolve("two"));
        var history = new AgentInteractionHistory();
        for (int i = 0; i < 60; i++) history.add(one, new AgentInteractionHistory.Entry(Instant.now(), AgentAction.RUN, "task " + i, "proposal-" + i, null, "summary"));
        assertEquals(50, history.entries(one).size()); assertEquals("proposal-59", history.entries(one).getFirst().proposal());
        assertTrue(history.entries(two).isEmpty());
        history.add(two, new AgentInteractionHistory.Entry(Instant.now(), AgentAction.ASK, "question", "", null, "answer"));
        history.clear(one); assertTrue(history.entries(one).isEmpty()); assertEquals(1, history.entries(two).size());
    }
    @Test void privateWorkDescriptionPersistsPerWorkspaceAndCanBeCleared() throws Exception {
        Path one = Files.createDirectory(temporary.resolve("one")), two = Files.createDirectory(temporary.resolve("two")), storage = temporary.resolve("sessions");
        assertEquals("", AgentInteractionHistory.loadWork(storage, one));
        AgentInteractionHistory.saveWork(storage, one, "Finish search\nKeep cancellation");
        assertEquals("Finish search\nKeep cancellation", AgentInteractionHistory.loadWork(storage, one));
        assertEquals("", AgentInteractionHistory.loadWork(storage, two));
        assertTrue(AgentInteractionHistory.workPrompt(AgentInteractionHistory.loadWork(storage, one), false).contains("cite relevant locations"));
        assertTrue(AgentInteractionHistory.workPrompt("work", true).contains("propose remaining changes"));
        AgentInteractionHistory.saveWork(storage, one, ""); assertEquals("", AgentInteractionHistory.loadWork(storage, one));
        assertThrows(IllegalArgumentException.class, () -> AgentInteractionHistory.saveWork(storage, one, "x".repeat(7501)));
        assertThrows(IllegalArgumentException.class, () -> AgentInteractionHistory.workPrompt("", true));
        assertTrue(AgentInteractionHistory.workPrompt("x".repeat(7500), false).length() < 8192);
        assertEquals(0, Files.list(one).count());
    }
}
