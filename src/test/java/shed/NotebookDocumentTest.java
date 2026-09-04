package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class NotebookDocumentTest {
    @Test
    void parsesSourceArraysAndPreservesOutputsWhenSerializing() {
        String source = "{\"nbformat\":4,\"nbformat_minor\":5,\"metadata\":{},\"cells\":[{\"cell_type\":\"code\",\"source\":[\"print(1)\\n\"],\"execution_count\":1,\"outputs\":[{\"output_type\":\"stream\",\"text\":\"1\\n\"}]}]}";
        NotebookDocument document = NotebookDocument.parse(source);

        assertEquals("print(1)\n", document.cells().getFirst().source());
        assertEquals("1\n", NotebookDocument.outputText(document.cells().getFirst()));
        assertTrue(document.serialize().contains("\"outputs\""));
    }

    @Test
    void createsValidCodeCellsForNewNotebookContent() {
        NotebookDocument document = NotebookDocument.empty().withCells(List.of(new NotebookDocument.Cell("code", "x = 1", Map.of())));
        assertTrue(document.serialize().contains("\"execution_count\":null"));
        assertTrue(document.serialize().contains("\"outputs\":[]"));
    }

    @Test
    void mergesOnlyTheExecutedCellPrefix() {
        NotebookDocument document = NotebookDocument.empty().withCells(List.of(
            new NotebookDocument.Cell("code", "first", Map.of("outputs", List.of())),
            new NotebookDocument.Cell("code", "second", Map.of("outputs", List.of()))));
        NotebookDocument executed = NotebookDocument.empty().withCells(List.of(
            new NotebookDocument.Cell("code", "first", Map.of("outputs", List.of(Map.of("text", "done"))))));

        NotebookDocument merged = document.withExecutedPrefix(executed, 1);

        assertEquals("done", NotebookDocument.outputText(merged.cells().getFirst()));
        assertEquals("second", merged.cells().get(1).source());
    }
}
