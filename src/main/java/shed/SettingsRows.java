package shed;

import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.function.Function;
import javax.swing.*;

/** Keeps filtered rows attached so Swing need not recreate their controls and HTML views. */
final class SettingsRows extends JPanel implements Scrollable {
    private record Row(SettingsApplication.Setting setting,Object appearance,JComponent component) { }
    private final Map<String,Row> rows=new LinkedHashMap<>();
    private final Set<String> invalidated=new HashSet<>();
    private final JLabel empty=new JLabel("No settings match this search.");
    private final JPanel filler=new JPanel();
    private Object appearance;

    SettingsRows(){super(new GridBagLayout());add(empty);add(filler);}
    void invalidate(String key){invalidated.add(key);}

    /** Returns whether new rows or an appearance change require styling. */
    boolean showRows(List<SettingsApplication.Setting> catalog,List<SettingsApplication.Setting> visible,
                     Object appearance,Function<SettingsApplication.Setting,JComponent> create,Insets insets){
        if(!SwingUtilities.isEventDispatchThread())throw new IllegalStateException("Settings rows belong on the EDT");
        var keys=new HashSet<String>();for(var setting:catalog)keys.add(setting.key());
        rows.entrySet().removeIf(entry->{if(keys.contains(entry.getKey()))return false;remove(entry.getValue().component());return true;});
        invalidated.retainAll(keys);
        var shown=new HashSet<String>();for(var setting:visible)shown.add(setting.key());
        for(var entry:rows.entrySet())if(!shown.contains(entry.getKey()))visibility(entry.getValue().component(),false);
        boolean changedAppearance=!Objects.equals(this.appearance,appearance);this.appearance=appearance;
        boolean created=false;int index=0;
        for(var setting:visible){
            var row=rows.get(setting.key());
            boolean dirty=invalidated.remove(setting.key());
            if(row==null||!row.setting().equals(setting)||!Objects.equals(row.appearance(),appearance)||dirty){
                if(row!=null)remove(row.component());
                row=new Row(setting,appearance,create.apply(setting));rows.put(setting.key(),row);add(row.component());created=true;
            }
            visibility(row.component(),true);place(row.component(),index++,0,insets);
        }
        empty.setVisible(index==0);if(index==0)place(empty,index++,0,insets);
        place(filler,index,1,insets);revalidate();repaint();return created||changedAppearance;
    }
    private static void visibility(JComponent row,boolean visible){
        row.putClientProperty("shed.agent.disabled",visible?null:Boolean.TRUE);row.setVisible(visible);
    }
    private void place(Component component,int row,double weight,Insets insets){
        var constraints=new GridBagConstraints();constraints.gridx=0;constraints.gridy=row;constraints.weightx=1;constraints.weighty=weight;
        constraints.anchor=GridBagConstraints.WEST;constraints.fill=GridBagConstraints.HORIZONTAL;constraints.insets=insets;
        ((GridBagLayout)getLayout()).setConstraints(component,constraints);
    }
    @Override public Dimension getPreferredScrollableViewportSize(){return getPreferredSize();}
    @Override public int getScrollableUnitIncrement(Rectangle visible,int orientation,int direction){return 32;}
    @Override public int getScrollableBlockIncrement(Rectangle visible,int orientation,int direction){return Math.max(32,visible.height-32);}
    @Override public boolean getScrollableTracksViewportWidth(){return true;}
    @Override public boolean getScrollableTracksViewportHeight(){return false;}
}
