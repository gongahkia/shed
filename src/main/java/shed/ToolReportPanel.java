package shed;

import java.awt.*;
import java.util.function.BiConsumer;
import javax.swing.*;

/** One bounded report per tool, independent of source, revisions and Undo history. */
final class ToolReportPanel extends JPanel {
    static final int MAX_TEXT=262144;
    private final JPanel cards=new JPanel(new CardLayout());
    private final JLabel title=new JLabel();
    private final JTextArea text=new JTextArea();
    private String exportTitle="Report";
    ToolReportPanel(JPanel primary,String tool,BiConsumer<String,String> export){
        super(new BorderLayout());setName("tool.report."+tool.toLowerCase(java.util.Locale.ROOT).replace(' ','-'));
        var report=new JPanel(new BorderLayout(6,6));var header=new JPanel(new BorderLayout(6,0));title.putClientProperty("html.disable",true);header.add(title);
        var actions=new JPanel(new FlowLayout(FlowLayout.TRAILING,4,0));var back=new JButton("Back to "+tool);back.addActionListener(e->primary());actions.add(back);
        var copy=new JButton("Export displayed text");copy.addActionListener(e->export.accept(exportTitle,text.getText()));actions.add(copy);header.add(actions,BorderLayout.SOUTH);
        text.setEditable(false);text.setName("tool.report.text");AccessibilitySupport.describe(text,tool+" report","Read-only report. Export displayed text explicitly to open an editable copy.");
        report.setBorder(BorderFactory.createEmptyBorder(6,8,8,8));report.add(header,BorderLayout.NORTH);report.add(new JScrollPane(text));cards.add(primary,"primary");cards.add(report,"report");add(cards);
    }
    void show(String name,String value){exportTitle=name;title.setText(name);String shown=bounded(value);if(!text.getText().equals(shown)){text.setText(shown);text.setCaretPosition(0);}((CardLayout)cards.getLayout()).show(cards,"report");}
    static String bounded(String value){String text=value==null?"":value;return text.length()<=MAX_TEXT?text:text.substring(0,GraphemeBoundary.floor(text,MAX_TEXT))+"\n[Report shortened; refine the query for remaining output.]\n";}
    void primary(){((CardLayout)cards.getLayout()).show(cards,"primary");}
    void appearance(Font ui,Font code){title.setFont(ui);text.setFont(code);}
    String displayed(){return text.getText();}
}
