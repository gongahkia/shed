package shed;

import java.awt.*;
import java.awt.event.*;
import java.util.List;
import javax.swing.*;

/** Presentation of the existing revision-validated symbol cache; no parser or document listener. */
final class OutlinePanel extends JPanel {
    private final Texteditor editor;
    private final DefaultListModel<SymbolService.Symbol> model=new DefaultListModel<>();
    private final JList<SymbolService.Symbol> list=new JList<>(model);
    private final JLabel state=new JLabel("Open a source file to see symbols.");
    private final JTextField filter=new JTextField();
    private FileBuffer buffer;
    private VersionedTextSnapshot text;
    private List<SymbolService.Symbol> symbols=List.of(), input=List.of();
    OutlinePanel(Texteditor editor){
        super(new BorderLayout(4,4));this.editor=editor;setName("workbench.outline");
        var heading=new JPanel(new BorderLayout());heading.add(new JLabel("Outline"));var close=new JButton(WorkbenchIcons.CLOSE);close.setToolTipText("Hide Outline");close.addActionListener(e->editor.toggleOutline());WorkbenchStyle.quiet(close);heading.add(close,BorderLayout.EAST);
        var top=new JPanel(new BorderLayout(4,4));top.add(heading,BorderLayout.NORTH);top.add(filter);add(top,BorderLayout.NORTH);add(new JScrollPane(list));add(state,BorderLayout.SOUTH);
        list.setName("outline.symbols");AccessibilitySupport.describe(list,"Document outline","Classes, functions and headings. Enter reveals the selected symbol in the source.");AccessibilitySupport.describe(filter,"Filter symbols","Filter symbols in the current source file.");
        list.setCellRenderer(new DefaultListCellRenderer(){public Component getListCellRendererComponent(JList<?> l,Object value,int index,boolean chosen,boolean focus){var label=(JLabel)super.getListCellRendererComponent(l,value,index,chosen,focus);label.putClientProperty("html.disable",true);if(value instanceof SymbolService.Symbol s)label.setText("  ".repeat(Math.min(8,s.getLevel()-1))+s.getName()+"  · "+s.getKind()+" :"+s.getLine());return label;}});
        filter.getDocument().addDocumentListener(new javax.swing.event.DocumentListener(){public void insertUpdate(javax.swing.event.DocumentEvent e){filter();}public void removeUpdate(javax.swing.event.DocumentEvent e){filter();}public void changedUpdate(javax.swing.event.DocumentEvent e){filter();}});
        list.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER,0),"reveal");list.getActionMap().put("reveal",new AbstractAction(){public void actionPerformed(ActionEvent e){reveal();}});
        list.addMouseListener(new MouseAdapter(){public void mouseClicked(MouseEvent e){if(e.getClickCount()==2)reveal();}});KeyboardFocusSupport.installEscape(this,editor::requestActivePaneFocus);
    }
    void update(FileBuffer buffer,VersionedTextSnapshot text,List<SymbolService.Symbol> values){
        if(this.buffer==buffer&&this.text==text&&this.input==values)return;
        this.buffer=buffer;this.text=text;this.input=values;this.symbols=values.size()>2000?List.copyOf(values.subList(0,2000)):values;filter();
        state.setText(buffer==null?"Open a source file to see symbols.":buffer.isLargeFile()?"Outline unavailable in large-file mode.":values.isEmpty()?"No cached symbols. Supported languages update after editing settles.":values.size()>2000?"Showing the first 2,000 symbols.":"Local symbols · "+buffer.getFileType()+" · "+values.size());
    }
    private void filter(){String q=filter.getText().trim().toLowerCase(java.util.Locale.ROOT);model.clear();for(var s:symbols)if(s.getName().toLowerCase(java.util.Locale.ROOT).contains(q))model.addElement(s);}
    private void reveal(){
        var symbol=list.getSelectedValue();if(symbol==null||editor.agentOwnsEditor()||buffer!=editor.getCurrentBuffer()||text!=buffer.textSnapshot())return;
        try{int line=symbol.getLine()-1;if(line<0||line>=editor.writingArea.getLineCount())return;int offset=editor.writingArea.getLineStartOffset(line);editor.recordJumpPosition();editor.writingArea.setCaretPosition(offset);var bounds=editor.writingArea.modelToView2D(offset);if(bounds!=null)editor.writingArea.scrollRectToVisible(bounds.getBounds());editor.requestActivePaneFocus();}catch(javax.swing.text.BadLocationException unavailable){state.setText("Symbol location unavailable. Wait for the outline to refresh.");}
    }
}
