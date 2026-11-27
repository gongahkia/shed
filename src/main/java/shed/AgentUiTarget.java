package shed;

import java.awt.*;
import java.util.*;
import java.util.List;
import javax.swing.*;
import javax.swing.text.JTextComponent;

/** A live semantic target, rebuilt from existing controllers; never a persistent component cache. */
record AgentUiTarget(String id,String label,JComponent component,AgentUiPermission permission,Action action) {
    interface Action { void run(String value); }
    AgentUiTarget {
        if(id==null||!id.matches("[A-Za-z0-9_.:/-]{1,160}")||component==null||permission==null)throw new IllegalArgumentException("Invalid UI target");
    }
    String role(){
        if(action!=null)return "action";
        if(component instanceof JTextComponent)return "text";
        if(component instanceof AbstractButton)return "button";
        if(component instanceof JComboBox<?>)return "combobox";
        if(component instanceof JTabbedPane)return "tabs";
        if(component instanceof JList<?>)return "list";
        if(component instanceof JTable)return "table";
        if(component instanceof JTree)return "tree";
        if(component instanceof JSplitPane)return "split";
        if(component instanceof JScrollPane)return "viewport";
        if(component instanceof JScrollBar)return "scrollbar";
        return "surface";
    }
    Point point(int index){
        Rectangle bounds=null;
        if(index>=0){
            if(component instanceof JList<?> list&&index<list.getModel().getSize())bounds=list.getCellBounds(index,index);
            else if(component instanceof JTable table&&index<table.getRowCount()&&table.getColumnCount()>0)bounds=table.getCellRect(index,0,true);
            else if(component instanceof JTree tree&&index<tree.getRowCount())bounds=tree.getRowBounds(index);
            else if(component instanceof JTabbedPane tabs&&index<tabs.getTabCount())bounds=tabs.getBoundsAt(index);
        }
        if(bounds==null)bounds=component.getVisibleRect();
        return new Point((int)bounds.getCenterX(),(int)bounds.getCenterY());
    }
    Map<String,Object> metadata(boolean permitted){
        var m=new LinkedHashMap<String,Object>();m.put("id",id);m.put("label",bounded(label,100));m.put("role",role());
        m.put("permission",permission.name().toLowerCase(Locale.ROOT));m.put("enabled",permitted&&component.isEnabled());m.put("visible",component.isShowing());
        if(component instanceof JTextComponent text){m.put("length",text.getDocument().getLength());m.put("editable",text.isEditable());}
        if(component instanceof JList<?> list){m.put("count",list.getModel().getSize());m.put("selected",list.getSelectedIndex());}
        if(component instanceof JTable table){m.put("count",table.getRowCount());m.put("selected",table.getSelectedRow());}
        if(component instanceof JTree tree){m.put("count",tree.getRowCount());m.put("selected",tree.getMinSelectionRow());}
        if(component instanceof JComboBox<?> combo){m.put("count",combo.getItemCount());m.put("selected",combo.getSelectedIndex());}
        if(component instanceof AbstractButton button)m.put("selected",button.isSelected());
        return m;
    }
    List<Map<String,Object>> items(int offset){
        var values=new ArrayList<Map<String,Object>>();
        for(int i=offset;i<offset+24;i++){
            String text;
            if(component instanceof JList<?> list){if(i>=list.getModel().getSize())break;var value=list.getModel().getElementAt(i);
                @SuppressWarnings({"rawtypes","unchecked"}) Component rendered=((ListCellRenderer)list.getCellRenderer()).getListCellRendererComponent(list,value,i,false,false);
                text=rendered instanceof JLabel label?label.getText():String.valueOf(value);
            }else if(component instanceof JTable table){if(i>=table.getRowCount())break;var row=new StringBuilder();for(int c=0;c<Math.min(4,table.getColumnCount());c++){if(c>0)row.append(" · ");row.append(bounded(String.valueOf(table.getValueAt(i,c)),120));}text=row.toString();
            }else if(component instanceof JTree tree){if(i>=tree.getRowCount())break;var path=tree.getPathForRow(i);text=tree.convertValueToText(path.getLastPathComponent(),false,tree.isExpanded(path),tree.getModel().isLeaf(path.getLastPathComponent()),i,false);
            }else if(component instanceof JComboBox<?> combo){if(i>=combo.getItemCount())break;text=String.valueOf(combo.getItemAt(i));
            }else if(component instanceof JTabbedPane tabs){if(i>=tabs.getTabCount())break;text=tabs.getTitleAt(i);
            }else break;
            values.add(Map.of("index",i,"label",bounded(text,256)));
        }
        return values;
    }
    static String bounded(String value,int length){if(value==null)return "";if(value.length()<=length)return value;int end=length;if(Character.isHighSurrogate(value.charAt(end-1)))end--;return value.substring(0,end)+"…";}
}
