package shed;

import java.awt.*;
import java.util.List;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;

/** Navigates the existing quickfix service without creating a source document. */
final class QuickfixToolPanel implements ToolWindowHost.ToolSurface {
    private final Texteditor editor;
    private final JPanel panel=new JPanel(new BorderLayout(6,6));
    private final DefaultTableModel rows=new DefaultTableModel(new Object[]{"File","Location","Message"},0){public boolean isCellEditable(int row,int column){return false;}};
    private final JTable table=new JTable(rows);
    private final JLabel title=new JLabel();
    private List<QuickfixService.Entry> entries=List.of();
    QuickfixToolPanel(Texteditor editor){
        this.editor=editor;table.setName("quickfix.results");table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);table.setDefaultRenderer(Object.class,new javax.swing.table.DefaultTableCellRenderer(){public Component getTableCellRendererComponent(JTable t,Object value,boolean selected,boolean focus,int row,int column){var label=(JLabel)super.getTableCellRendererComponent(t,value,selected,focus,row,column);label.putClientProperty("html.disable",true);return label;}});
        title.putClientProperty("html.disable",true);panel.add(title,BorderLayout.NORTH);panel.add(new JScrollPane(table));var actions=new JPanel(new FlowLayout(FlowLayout.LEADING));var open=new JButton("Open location");open.addActionListener(e->open());actions.add(open);panel.add(actions,BorderLayout.SOUTH);
        table.getInputMap().put(KeyStroke.getKeyStroke("ENTER"),"open");table.getActionMap().put("open",new AbstractAction(){public void actionPerformed(java.awt.event.ActionEvent e){open();}});
        table.addMouseListener(new java.awt.event.MouseAdapter(){public void mouseClicked(java.awt.event.MouseEvent e){if(e.getClickCount()==2&&SwingUtilities.isLeftMouseButton(e))open();}});
        AccessibilitySupport.describe(table,"Navigation results","Select a quickfix or search result and press Enter to open its source location.");
    }
    public JPanel component(){return panel;}
    public void focus(){table.requestFocusInWindow();}
    public void refresh(){var all=editor.quickfixService.entries();int total=all.size(),count=Math.min(total,10000);entries=List.copyOf(all.subList(0,count));rows.setRowCount(0);for(var entry:entries.subList(0,count))rows.addRow(new Object[]{entry.getFilePath(),entry.getLine()+":"+entry.getColumn(),entry.getMessage()});title.setText(editor.quickfixService.getTitle()+" · "+count+" results"+(count<total?" (display limited to 10,000)":""));int selected=editor.quickfixService.currentIndex();if(selected>=0&&selected<count)table.setRowSelectionInterval(selected,selected);}
    private void open(){int row=table.getSelectedRow();if(row<0||row>=entries.size())return;var current=editor.quickfixService.entries();if(row>=current.size()||current.get(row)!=entries.get(row)){refresh();editor.showMessage("Results changed; select the location again.");return;}editor.showMessage(editor.quickfixCurrent(Integer.toString(row+1)));}
}
