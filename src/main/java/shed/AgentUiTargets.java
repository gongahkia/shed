package shed;

import java.awt.*;
import java.util.*;
import java.util.List;
import javax.swing.*;
import javax.swing.border.TitledBorder;
import javax.swing.text.JTextComponent;

/** Semantic IDs derived from deliberately admitted surfaces and their existing names/labels. */
final class AgentUiTargets {
    static final String ACTION="shed.agent.action",PERMISSION="shed.agent.permission";
    private AgentUiTargets(){}
    static String slug(String text){return Objects.toString(text,"").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+","-").replaceAll("^-|-$","");}
    static void action(AbstractButton button,AgentUiPermission permission,Runnable action){button.putClientProperty(PERMISSION,permission);button.putClientProperty(ACTION,action);}
    static void collect(JComponent root,String prefix,AgentUiPermission permission,List<AgentUiTarget> result){
        var candidates=new LinkedHashMap<String,AgentUiTarget>();var duplicates=new HashSet<String>();
        var labels=new IdentityHashMap<Component,String>();collectLabels(root,labels,0);
        visit(root,prefix,permission,candidates,duplicates,labels,new int[]{0},0);
        for(var entry:candidates.entrySet())if(!duplicates.contains(entry.getKey()))result.add(entry.getValue());
    }
    private static void collectLabels(Component component,Map<Component,String> labels,int depth){
        if(depth>24)return;if(component instanceof JLabel label&&label.getLabelFor()!=null)labels.put(label.getLabelFor(),label.getText());
        if(component instanceof Container parent)for(Component child:parent.getComponents())collectLabels(child,labels,depth+1);
    }
    private static void visit(Component component,String prefix,AgentUiPermission inherited,Map<String,AgentUiTarget> entries,Set<String> duplicates,Map<Component,String> labels,int[] count,int depth){
        if(++count[0]>2400||depth>24||component instanceof JPasswordField||component instanceof JFileChooser||component instanceof JComponent c&&Boolean.TRUE.equals(c.getClientProperty("shed.agent.disabled")))return;
        String next=prefix;
        if(component instanceof JComponent control){
            var permitted=control.getClientProperty(PERMISSION) instanceof AgentUiPermission p?p:inherited;
            String label=label(control,labels);String name=control.getName();
            if(control.getBorder() instanceof TitledBorder title&&!slug(title.getTitle()).isEmpty())next=prefix+"/"+slug(title.getTitle());
            if(supported(control)){
                String key=name!=null&&!name.isBlank()?name:role(control)+"."+slug(label);
                if(key.endsWith("."))key=control instanceof JTextComponent text?text.isEditable()?"field.input":"field.output":control instanceof JComboBox<?>?"choice.value":"";
                if(!key.isEmpty()){
                    String id=next+"/"+key.replaceAll("[^A-Za-z0-9_.:/-]","-");
                    if(id.length()<=160){
                        var callback=control.getClientProperty(ACTION) instanceof Runnable run?(AgentUiTarget.Action)value->run.run():null;
                        var target=new AgentUiTarget(id,label,control,permitted,callback);
                        if(entries.putIfAbsent(id,target)!=null)duplicates.add(id);
                    }
                }
            }
            inherited=permitted;
        }
        if(component instanceof JMenu menu){for(Component child:menu.getMenuComponents())visit(child,next,inherited,entries,duplicates,labels,count,depth+1);}
        else if(component instanceof JComboBox<?>)return;
        else if(component instanceof Container parent)for(Component child:parent.getComponents())visit(child,next,inherited,entries,duplicates,labels,count,depth+1);
    }
    private static boolean supported(JComponent c){return c instanceof JTextComponent||c instanceof AbstractButton||c instanceof JComboBox<?>||c instanceof JList<?>||c instanceof JTable||c instanceof JTree||c instanceof JTabbedPane||c instanceof JSplitPane||c instanceof JScrollPane||c instanceof JScrollBar;}
    private static String role(JComponent c){return c instanceof AbstractButton?"button":c instanceof JTextComponent?"field":c instanceof JComboBox<?>?"choice":c instanceof JScrollPane?"viewport":c instanceof JScrollBar?"scrollbar":c instanceof JTabbedPane?"tabs":c instanceof JSplitPane?"divider":"items";}
    private static String label(JComponent c,Map<Component,String> labels){
        if(c instanceof AbstractButton button&&button.getText()!=null&&!button.getText().isBlank())return button.getText();
        if(labels.containsKey(c))return labels.get(c);
        if(c.getAccessibleContext()!=null){String name=c.getAccessibleContext().getAccessibleName();if(name!=null&&!name.isBlank())return name;}
        if(c.getToolTipText()!=null)return c.getToolTipText();
        return c.getName()==null?"":c.getName();
    }
}
