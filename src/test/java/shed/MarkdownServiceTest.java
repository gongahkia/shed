package shed;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

class MarkdownServiceTest {

    private MarkdownService service;

    @BeforeEach
    void setUp() {
        service = new MarkdownService();
    }

    // --- Heading detection ---

    @Test
    void headingLevel_detectsAllLevels() {
        assertEquals(1, service.headingLevel("# Title"));
        assertEquals(2, service.headingLevel("## Section"));
        assertEquals(3, service.headingLevel("### Subsection"));
        assertEquals(4, service.headingLevel("#### H4"));
        assertEquals(5, service.headingLevel("##### H5"));
        assertEquals(6, service.headingLevel("###### H6"));
    }

    @Test
    void headingLevel_nonHeadingReturnsZero() {
        assertEquals(0, service.headingLevel("Not a heading"));
        assertEquals(0, service.headingLevel(""));
        assertEquals(0, service.headingLevel(null));
        assertEquals(0, service.headingLevel("#NoSpace"));
        assertEquals(0, service.headingLevel("####### Too many"));
    }

    @Test
    void headingText_extractsText() {
        assertEquals("Title", service.headingText("# Title"));
        assertEquals("Section", service.headingText("## Section"));
    }

    @Test
    void isHeading_worksCorrectly() {
        assertTrue(service.isHeading("# Title"));
        assertFalse(service.isHeading("Not heading"));
    }

    // --- Fold range ---

    @Test
    void computeFoldRange_simpleSection() {
        String[] lines = {"# H1", "content", "more content"};
        MarkdownService.FoldRange range = service.computeFoldRange(lines, 0);
        assertNotNull(range);
        assertEquals(0, range.startLine);
        assertEquals(2, range.endLine);
        assertEquals(1, range.level);
    }

    @Test
    void computeFoldRange_stopsAtSameLevel() {
        String[] lines = {"# H1", "content", "# H1 Again"};
        MarkdownService.FoldRange range = service.computeFoldRange(lines, 0);
        assertNotNull(range);
        assertEquals(0, range.startLine);
        assertEquals(1, range.endLine);
    }

    @Test
    void computeFoldRange_includesSubheadings() {
        String[] lines = {"# H1", "## Sub", "content", "# H1 Again"};
        MarkdownService.FoldRange range = service.computeFoldRange(lines, 0);
        assertNotNull(range);
        assertEquals(0, range.startLine);
        assertEquals(2, range.endLine);
    }

    @Test
    void computeFoldRange_noContent() {
        String[] lines = {"# H1"};
        assertNull(service.computeFoldRange(lines, 0));
    }

    // --- Heading navigation ---

    @Test
    void nextHeading_findsNext() {
        String[] lines = {"# A", "text", "## B", "text", "# C"};
        assertEquals(2, service.nextHeading(lines, 0));
        assertEquals(4, service.nextHeading(lines, 2));
        assertEquals(-1, service.nextHeading(lines, 4));
    }

    @Test
    void prevHeading_findsPrevious() {
        String[] lines = {"# A", "text", "## B", "text", "# C"};
        assertEquals(2, service.prevHeading(lines, 4));
        assertEquals(0, service.prevHeading(lines, 2));
        assertEquals(-1, service.prevHeading(lines, 0));
    }

    @Test
    void nextHeadingAtLevel_findsCorrectLevel() {
        String[] lines = {"# A", "## B", "### C", "## D", "# E"};
        assertEquals(3, service.nextHeadingAtLevel(lines, 1, 2));
        assertEquals(4, service.nextHeadingAtLevel(lines, 0, 1));
    }

    @Test
    void parentHeading_findsParent() {
        String[] lines = {"# A", "## B", "### C"};
        assertEquals(1, service.parentHeading(lines, 2));
        assertEquals(0, service.parentHeading(lines, 1));
        assertEquals(-1, service.parentHeading(lines, 0));
    }

    // --- Heading promotion/demotion ---

    @Test
    void promoteHeading_removesOneHash() {
        assertEquals("# Title", service.promoteHeading("## Title"));
        assertEquals("## Title", service.promoteHeading("### Title"));
    }

    @Test
    void promoteHeading_stopsAtH1() {
        assertEquals("# Title", service.promoteHeading("# Title"));
    }

    @Test
    void demoteHeading_addsOneHash() {
        assertEquals("## Title", service.demoteHeading("# Title"));
        assertEquals("### Title", service.demoteHeading("## Title"));
    }

    @Test
    void demoteHeading_stopsAtH6() {
        assertEquals("###### Title", service.demoteHeading("###### Title"));
    }

    // --- TOC ---

    @Test
    void generateToc_listsHeadings() {
        String[] lines = {"# H1", "text", "## H2", "### H3"};
        String toc = service.generateToc(lines);
        assertTrue(toc.contains("H1"));
        assertTrue(toc.contains("H2"));
        assertTrue(toc.contains("H3"));
        assertTrue(toc.contains("line 1"));
        assertTrue(toc.contains("line 3"));
    }

    // --- Table operations ---

    @Test
    void isTableRow_detectsTableRows() {
        assertTrue(service.isTableRow("| a | b |"));
        assertTrue(service.isTableRow("| a | b | c |"));
        assertFalse(service.isTableRow("not a table"));
        assertFalse(service.isTableRow(""));
    }

    @Test
    void isTableSeparator_detectsSeparators() {
        assertTrue(service.isTableSeparator("| --- | --- |"));
        assertTrue(service.isTableSeparator("| :---: | ---: |"));
        assertFalse(service.isTableSeparator("| a | b |"));
    }

    @Test
    void parseCells_extractsCells() {
        String[] cells = service.parseCells("| a | b | c |");
        assertEquals(3, cells.length);
        assertEquals("a", cells[0]);
        assertEquals("b", cells[1]);
        assertEquals("c", cells[2]);
    }

    @Test
    void alignTable_alignsColumns() {
        String[] lines = {"| a | bb |", "| --- | --- |", "| ccc | d |"};
        String aligned = service.alignTable(lines, 0, 2);
        assertTrue(aligned.contains("|"));
        // All rows should have same width columns
        String[] resultLines = aligned.split("\n");
        assertEquals(3, resultLines.length);
    }

    @Test
    void sortTable_sortsData() {
        String[] lines = {"| name |", "| --- |", "| c |", "| a |", "| b |"};
        String sorted = service.sortTable(lines, 0, 4, 0, true);
        String[] resultLines = sorted.split("\n");
        assertTrue(resultLines.length >= 4);
        // Data rows should be sorted: a, b, c
        assertTrue(resultLines[2].contains("a"));
        assertTrue(resultLines[3].contains("b"));
        assertTrue(resultLines[4].contains("c"));
    }

    @Test
    void detectAlignments_parsesCorrectly() {
        String[] aligns = service.detectAlignments("| :---: | ---: | --- |");
        assertEquals("center", aligns[0]);
        assertEquals("right", aligns[1]);
        assertEquals("left", aligns[2]);
    }

    @Test
    void createTableTemplate_createsTable() {
        String table = service.createTableTemplate(3, 2);
        String[] lines = table.split("\n");
        assertEquals(4, lines.length); // header + separator + 2 data rows
        assertTrue(lines[0].startsWith("|"));
        assertTrue(lines[1].contains("---"));
    }

    // --- Checkbox ---

    @Test
    void isCheckbox_detectsCheckboxes() {
        assertTrue(service.isCheckbox("- [ ] task"));
        assertTrue(service.isCheckbox("- [x] done"));
        assertTrue(service.isCheckbox("- [X] done"));
        assertFalse(service.isCheckbox("- regular item"));
    }

    @Test
    void toggleCheckbox_togglesState() {
        assertEquals("- [x] task", service.toggleCheckbox("- [ ] task"));
        assertEquals("- [ ] task", service.toggleCheckbox("- [x] task"));
    }

    // --- Smart list continuation ---

    @Test
    void listContinuation_unorderedList() {
        assertEquals("- ", service.listContinuation("- item"));
        assertEquals("* ", service.listContinuation("* item"));
    }

    @Test
    void listContinuation_orderedList() {
        assertEquals("2. ", service.listContinuation("1. item"));
        assertEquals("6. ", service.listContinuation("5. item"));
    }

    @Test
    void listContinuation_checkbox() {
        String result = service.listContinuation("- [ ] task");
        assertNotNull(result);
        assertTrue(result.contains("[ ]"));
    }

    @Test
    void listContinuation_emptyItemReturnsNull() {
        assertNull(service.listContinuation("- "));
        assertNull(service.listContinuation("1. "));
    }

    @Test
    void listContinuation_nonListReturnsNull() {
        assertNull(service.listContinuation("regular text"));
    }

    // --- Link extraction ---

    @Test
    void extractLinkUrl_findsUrl() {
        String line = "check [this link](https://example.com) now";
        String url = service.extractLinkUrl(line, 10);
        assertEquals("https://example.com", url);
    }

    @Test
    void extractLinkUrl_returnsNullOutside() {
        String line = "no [link](url) here";
        assertNull(service.extractLinkUrl(line, 0));
    }

    // --- Horizontal rule ---

    @Test
    void isHorizontalRule_detectsRules() {
        assertTrue(service.isHorizontalRule("---"));
        assertTrue(service.isHorizontalRule("***"));
        assertTrue(service.isHorizontalRule("___"));
        assertTrue(service.isHorizontalRule("  ---  "));
        assertFalse(service.isHorizontalRule("--"));
        assertFalse(service.isHorizontalRule("text"));
    }

    // --- Code fence languages ---

    @Test
    void filterCodeFenceLanguages_filtersCorrectly() {
        String[] results = service.filterCodeFenceLanguages("ja");
        assertTrue(results.length > 0);
        assertEquals("java", results[0]);
        assertTrue(results.length >= 2); // java, javascript
    }

    // --- Subtree operations ---

    @Test
    void promoteSubtree_promotesAllHeadings() {
        String[] lines = {"## H2", "### H3", "text", "## Another"};
        String[] result = service.promoteSubtree(lines, 0);
        assertEquals("# H2", result[0]);
        assertEquals("## H3", result[1]);
        assertEquals("text", result[2]);
        assertEquals("## Another", result[3]); // not in subtree
    }

    @Test
    void demoteSubtree_demotesAllHeadings() {
        String[] lines = {"# H1", "## H2", "text", "# Another"};
        String[] result = service.demoteSubtree(lines, 0);
        assertEquals("## H1", result[0]);
        assertEquals("### H2", result[1]);
        assertEquals("text", result[2]);
        assertEquals("# Another", result[3]); // not in subtree
    }
}
