package shed;

import java.net.URL;
import java.util.Arrays;
import javax.swing.text.Document;
import javax.swing.text.html.HTMLDocument;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.StyleSheet;

/** Keeps HTMLReader's per-line ElementSpecs from copying an entire PRE text buffer. */
final class MarkdownPreviewHtmlKit extends HTMLEditorKit {
    @Override public Document createDefaultDocument() {
        // Match HTMLEditorKit's factory, including its isolated style sheet and parser settings.
        StyleSheet styles = new StyleSheet();
        styles.addStyleSheet(getStyleSheet());
        HTMLDocument document = new HTMLDocument(styles) {
            @Override public ParserCallback getReader(int position) {
                Object description = getProperty(Document.StreamDescriptionProperty);
                if (description instanceof URL url) setBase(url);
                return new HTMLReader(position) {
                    @Override protected void addContent(char[] data, int offset, int length,
                                                        boolean impliedParagraph) {
                        // JDK 21 ElementSpec defensively copies data.length, not length.
                        // preContent passes the whole block once per line. Pass exactly the
                        // requested range; leave text, attributes and paragraph handling to Swing.
                        if (offset != 0 || length != data.length) {
                            data = Arrays.copyOfRange(data, offset, offset + length);
                            offset = 0;
                        }
                        super.addContent(data, offset, length, impliedParagraph);
                    }
                };
            }
        };
        document.setParser(getParser());
        document.setAsynchronousLoadPriority(4);
        document.setTokenThreshold(100);
        return document;
    }
}
