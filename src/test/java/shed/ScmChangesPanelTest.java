package shed;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import javax.swing.*;
import org.junit.jupiter.api.Test;

class ScmChangesPanelTest {
    @Test void repositoryControlledNamesRemainPlainTextOnTheFirstRender()throws Exception{
        SwingUtilities.invokeAndWait(()->{
            String text="<html><b>literal branch or file</b></html>";
            var picker=ScmChangesPanel.branchPicker(List.of(new ScmWorkbenchModel.Branch("a".repeat(40),text)));
            var label=(JLabel)picker.getRenderer().getListCellRendererComponent(new JList<>(),picker.getItemAt(0),0,false,false);
            assertEquals(text,label.getText());assertNull(label.getClientProperty(javax.swing.plaf.basic.BasicHTML.propertyKey));
            var cell=(JLabel)ScmChangesPanel.plainCellRenderer().getTableCellRendererComponent(new JTable(1,1),text,false,false,0,0);
            assertEquals(text,cell.getText());assertNull(cell.getClientProperty(javax.swing.plaf.basic.BasicHTML.propertyKey));
        });
    }
    @Test void replacingRowsNotifiesOnceAndPreservesColumns()throws Exception{
        SwingUtilities.invokeAndWait(()->{
            var model=new ScmChangesPanel.RowsModel("State","Path");var table=new JTable(model);var column=table.getColumnModel().getColumn(1);column.setPreferredWidth(271);
            var events=new java.util.ArrayList<javax.swing.event.TableModelEvent>();model.addTableModelListener(events::add);
            var rows=new java.util.ArrayList<Object[]>();for(int i=0;i<5000;i++)rows.add(new Object[]{"Modified","file-"+i});model.replaceRows(rows);
            assertEquals(1,events.size());assertEquals(0,events.getFirst().getFirstRow());assertEquals(5000,table.getRowCount());assertEquals("file-4999",table.getValueAt(4999,1));assertSame(column,table.getColumnModel().getColumn(1));assertEquals(271,column.getPreferredWidth());assertFalse(model.isCellEditable(0,0));
            assertThrows(IllegalArgumentException.class,()->model.replaceRows(java.util.Collections.singletonList(new Object[]{"invalid"})));assertEquals(5000,model.getRowCount());assertEquals(1,events.size());
            model.replaceRows(List.of());assertEquals(0,model.getRowCount());assertEquals(2,events.size());
        });
    }
    @Test void rowInstallationRejectsOffEdtMutation(){
        var model=new ScmChangesPanel.RowsModel("State","Path");assertThrows(IllegalStateException.class,()->model.replaceRows(List.of()));assertEquals(0,model.getRowCount());
    }

}
