package shed;

import java.awt.*;
import java.util.function.BiConsumer;
import javax.swing.*;

/** A central, bounded comparison view; never a source buffer or an edit transaction. */
final class ReadOnlyComparisonPanel extends JPanel {
    private final ChangeDiffView view=new ChangeDiffView();
    ReadOnlyComparisonPanel(String title,String patch,Font font,Runnable back,BiConsumer<String,String> export){
        super(new BorderLayout(6,6));setName("workbench.comparison");var heading=new JLabel(title);heading.putClientProperty("html.disable",true);
        var controls=new JPanel(new BorderLayout());controls.add(heading);var actions=new JPanel(new FlowLayout(FlowLayout.TRAILING));var copy=new JButton("Export displayed patch");copy.addActionListener(e->export.accept(title,view.getText()));actions.add(copy);var close=new JButton("Back to source",WorkbenchIcons.CLOSE);close.setName("comparison.close");close.addActionListener(e->back.run());actions.add(close);controls.add(actions,BorderLayout.EAST);add(controls,BorderLayout.NORTH);view.setName("comparison.text");view.showPatch(patch,font);add(new JScrollPane(view));KeyboardFocusSupport.installEscape(this,back);
    }
    void appearance(Font font){view.refreshAppearance(font);}
    String displayed(){return view.getText();}
}
