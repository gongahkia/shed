package shed;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.Font;
import javax.swing.*;
import javax.swing.text.StyleConstants;
import org.junit.jupiter.api.Test;

/** In-memory styled text only; no HTML renderer, remote images, windows or subprocesses. */
class AgentChatTranscriptTest {
    @Test void messagesFormatCodeAndEmphasisWithoutExecutingModelHtml()throws Exception{
        SwingUtilities.invokeAndWait(()->{
            var view=new AgentChatTranscript();view.appearance(new Font(Font.DIALOG,Font.PLAIN,13));view.user("Explain this **literal input**");view.beginReply("Claude Code");
            view.updateReply("A **bold** answer and `code`.\n\n```java\nint value = 1;\n```\n\n<img src='https://invalid.test/private'>");view.finishReply();
            String text=view.getText();assertTrue(text.contains("You"));assertTrue(text.contains("Claude Code"));assertTrue(text.contains("**literal input**"));assertTrue(text.contains("int value = 1;"));assertFalse(text.contains("```"));assertTrue(text.contains("<img src="));
            int bold=text.indexOf("bold");assertTrue(StyleConstants.isBold(view.getStyledDocument().getCharacterElement(bold).getAttributes()));
            int code=text.indexOf("int value");assertEquals(Font.MONOSPACED,StyleConstants.getFontFamily(view.getStyledDocument().getCharacterElement(code).getAttributes()));
            view.appearance(new Font(Font.DIALOG,Font.PLAIN,16));assertEquals(text,view.getText());view.clearMessages();assertEquals("",view.getText());
        });
    }
    @Test void streamingAppendsPreserveSelectionAndChangedResultsDoNotDuplicate()throws Exception{
        SwingUtilities.invokeAndWait(()->{
            var view=new AgentChatTranscript();view.user("Question");view.beginReply("Codex");view.updateReply("Hello");view.select(0,3);view.updateReply("Hello world");assertEquals(0,view.getSelectionStart());assertEquals(3,view.getSelectionEnd());
            view.updateReply("Corrected answer");view.finishReply();assertFalse(view.getText().contains("Hello"));assertEquals(1,view.getText().split("Corrected answer",-1).length-1);
            for(int i=0;i<30;i++)view.user("x".repeat(4096));assertTrue(view.getText().length()<100000);
        });
    }
}
