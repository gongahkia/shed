package shed;

import java.util.*;
import javax.accessibility.*;

/** Uses actual Swing/Compose accessibility bridges, not test-only callbacks. EDT only. */
final class LabAccessibility {
    static List<AccessibleContext> contexts(Accessible root) {
        List<AccessibleContext> result = new ArrayList<>();
        Set<AccessibleContext> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        visit(root, result, seen, 0);
        return result;
    }
    private static void visit(Accessible accessible, List<AccessibleContext> result, Set<AccessibleContext> seen, int depth) {
        if(accessible == null || depth > 24 || result.size() > 5000) return;
        AccessibleContext context = accessible.getAccessibleContext();
        if(context == null || !seen.add(context)) return;
        result.add(context);
        for(int i=0;i<context.getAccessibleChildrenCount();i++) visit(context.getAccessibleChild(i),result,seen,depth+1);
    }
    static boolean edit(Accessible root, String name, String text) {
        for(var context:contexts(root)) if(name.equals(context.getAccessibleName()) && context.getAccessibleEditableText()!=null) {
            context.getAccessibleEditableText().setTextContents(text);return true;
        }
        return false;
    }
    static boolean action(Accessible root,String name) {
        for(var context:contexts(root)) if(name.equals(context.getAccessibleName()) && context.getAccessibleAction()!=null) {
            return context.getAccessibleAction().doAccessibleAction(0);
        }
        return false;
    }
    static List<Map<String,Object>> describe(Accessible root) {
        List<Map<String,Object>> rows=new ArrayList<>();
        for(var c:contexts(root)) {
            if(c.getAccessibleName()==null && c.getAccessibleEditableText()==null && c.getAccessibleAction()==null)continue;
            Map<String,Object> row=new LinkedHashMap<>();row.put("name",c.getAccessibleName());
            row.put("role",String.valueOf(c.getAccessibleRole()));row.put("editable",c.getAccessibleEditableText()!=null);
            row.put("actions",c.getAccessibleAction()==null?0:c.getAccessibleAction().getAccessibleActionCount());rows.add(row);
        }
        return rows;
    }
}
