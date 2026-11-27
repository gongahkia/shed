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
}
