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
    void listsOnlyStructuredJupyterKernelSpecs() throws Exception {
        assertEquals(List.of("jupyter", "kernelspec", "list", "--json"), NotebookController.kernelListCommand());
        assertEquals(List.of(new NotebookController.KernelSpec("python3", "Python 3", "python"),
            new NotebookController.KernelSpec("typescript", "typescript", "typescript")),
            NotebookController.parseKernelSpecs("{\"kernelspecs\":{\"typescript\":{\"spec\":{\"language\":\"typescript\"}},\"python3\":{\"spec\":{\"display_name\":\"Python 3\",\"language\":\"python\"}}}}"));
        org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class, () -> NotebookController.parseKernelSpecs("{}"));
    }

    @Test
    void persistsASelectedKernelspecAndUsesItWhenNoOneShotOverrideIsProvided() {
        NotebookDocument document = NotebookDocument.parse("{\"nbformat\":4,\"nbformat_minor\":5,\"metadata\":{\"custom\":true},\"cells\":[]}")
            .withKernelSpec("python3", "Python 3", "python");

        assertEquals("python3", document.kernelName());
        assertEquals("python3", NotebookController.selectedKernel(document, ""));
        assertEquals("typescript", NotebookController.selectedKernel(document, "typescript"));
        assertTrue(document.serialize().contains("\"custom\":true"));
        assertTrue(document.serialize().contains("\"kernelspec\""));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> document.withKernelSpec("python3; bad", "", ""));
    }

    @Test
    void notebookSurfaceStagesAPickerSelectionWithTheEditableDocument() {
        NotebookPanel panel = new NotebookPanel(NotebookDocument.empty(), ignored -> { }, ignored -> { }, (ignored, count) -> { }, null, () -> { });

        panel.selectKernel(new NotebookController.KernelSpec("python3", "Python 3", "python"));

        assertEquals("python3", panel.selectedKernelName());
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
    void extractsBoundedMarkdownDisplayDataForTheSanitizedNotebookView() {
        NotebookDocument document = NotebookDocument.empty().withCells(List.of(new NotebookDocument.Cell("code", "", Map.of("outputs", List.of(
            Map.of("data", Map.of("text/markdown", List.of("# Result\n", "<script>bad()</script>"), "text/html", "<b>ignored</b>")))))));

        List<NotebookDocument.MarkdownOutput> outputs = NotebookDocument.markdownOutputs(document.cells().getFirst());

        assertEquals(List.of("# Result\n<script>bad()</script>"), outputs.stream().map(NotebookDocument.MarkdownOutput::markdown).toList());
        assertFalse(MarkdownPreviewRenderer.renderBasic(outputs.getFirst().markdown(), "Notebook", null, null, null, null).contains("<script>"));
    }

    @Test
    void skipsOversizedMarkdownDisplayData() {
        NotebookDocument document = NotebookDocument.empty().withCells(List.of(new NotebookDocument.Cell("code", "", Map.of("outputs", List.of(
            Map.of("data", Map.of("text/markdown", "x".repeat(128 * 1024 + 1))))))));

        assertEquals(List.of(), NotebookDocument.markdownOutputs(document.cells().getFirst()));
    }

    @Test
    void rendersBoundedApplicationJsonAsInertTextWhenPlainTextIsUnavailable() {
        NotebookDocument document = NotebookDocument.empty().withCells(List.of(new NotebookDocument.Cell("code", "", Map.of("outputs", List.of(
            Map.of("data", Map.of("application/json", Map.of("count", 2L, "items", List.of("a", "b")))))))));

        assertEquals(Map.of("count", 2L, "items", List.of("a", "b")),
            MiniJson.asObject(MiniJson.parse(NotebookDocument.outputText(document.cells().getFirst()))));
    }

    @Test
    void skipsOversizedApplicationJsonDisplayData() {
        NotebookDocument document = NotebookDocument.empty().withCells(List.of(new NotebookDocument.Cell("code", "", Map.of("outputs", List.of(
            Map.of("data", Map.of("application/json", "x".repeat(128 * 1024))))))));

        assertEquals("", NotebookDocument.outputText(document.cells().getFirst()));
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
