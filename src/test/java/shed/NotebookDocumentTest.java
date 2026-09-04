package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import javax.imageio.ImageIO;
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

    @Test
    void buildsOnlyValidatedDirectArgvJupyterConsoleCommands() {
        assertEquals(List.of("jupyter", "console"), NotebookController.consoleCommand(""));
        assertEquals(List.of("jupyter", "console", "--kernel", "python3"), NotebookController.consoleCommand("python3"));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> NotebookController.consoleCommand("python3; bad"));
    }

    @Test
    void buildsOnlyValidatedDirectArgvJupyterExecutionCommands() {
        assertEquals(List.of("jupyter", "nbconvert", "--to", "notebook", "--execute", "--inplace", "/project/demo.ipynb"),
            NotebookController.executeCommand(Path.of("/project/demo.ipynb"), ""));
        assertEquals(List.of("jupyter", "nbconvert", "--to", "notebook", "--execute", "--inplace",
            "--ExecutePreprocessor.kernel_name=python3", "/project/demo.ipynb"),
            NotebookController.executeCommand(Path.of("/project/demo.ipynb"), "python3"));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
            () -> NotebookController.executeCommand(Path.of("/project/demo.ipynb"), "python3; bad"));
    }

    @Test
    void extractsOnlyBoundedPngAndJpegDisplayData() {
        NotebookDocument document = NotebookDocument.empty().withCells(List.of(new NotebookDocument.Cell("code", "", Map.of("outputs", List.of(
            Map.of("data", Map.of("image/png", "aGVsbG8=", "image/svg+xml", "PHN2Zz4=")))))));

        List<NotebookDocument.ImageOutput> images = NotebookDocument.imageOutputs(document.cells().getFirst());
        assertEquals(1, images.size());
        assertEquals("image/png", images.getFirst().mimeType());
        assertFalse(new String(images.getFirst().bytes(), java.nio.charset.StandardCharsets.UTF_8).contains("svg"));
    }

    @Test
    void decodesValidNotebookRasterOutputAfterHeaderChecks() throws Exception {
        BufferedImage source = new BufferedImage(3, 2, BufferedImage.TYPE_INT_ARGB);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(source, "png", bytes);

        BufferedImage decoded = NotebookPanel.decodeImage(new NotebookDocument.ImageOutput("image/png", bytes.toByteArray()));
        assertEquals(3, decoded.getWidth());
        assertEquals(2, decoded.getHeight());
    }
}
