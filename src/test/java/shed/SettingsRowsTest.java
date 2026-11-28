package shed;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.*;
import java.util.*;
import java.util.List;
import javax.swing.*;
import javax.swing.plaf.basic.BasicHTML;
import org.junit.jupiter.api.Test;

class SettingsRowsTest {
    private static SettingsApplication.Setting setting(String key,String value){return new SettingsApplication.Setting(key,"Editor","integer",value,"16","Description","integer","Live");}
    private static final class Fixture {
        final SettingsRows rows=new SettingsRows();
        final Map<String,JPanel> panels=new HashMap<>();
        int builds;
        boolean show(List<SettingsApplication.Setting> catalog,List<SettingsApplication.Setting> visible,Object appearance){
            return rows.showRows(catalog,visible,appearance,d->{
                builds++;var panel=new JPanel(new BorderLayout());var field=new JTextField(d.currentValue());field.setName(d.key());
                var detail=new JLabel("<html>"+d.description()+"<br>"+d.applyBehavior()+"</html>");detail.setName("description");
                panel.add(field);panel.add(detail,BorderLayout.SOUTH);panels.put(d.key(),panel);return panel;
            },new Insets(2,2,2,2));
        }
    }
    @Test void filteringKeepsControlsHtmlViewsDraftsAndParents()throws Exception{
        SwingUtilities.invokeAndWait(()->{
            var f=new Fixture();var a=setting("font.size","16");var b=setting("terminal.font.size","14");var all=List.of(a,b);
            assertTrue(f.show(all,all,"light"));var first=f.panels.get(a.key());var second=f.panels.get(b.key());
            var field=(JTextField)first.getComponent(0);field.setText("invalid draft");field.select(2,5);
            var label=(JLabel)first.getComponent(1);var html=label.getClientProperty(BasicHTML.propertyKey);assertNotNull(html);
            for(int i=0;i<20;i++){
                assertFalse(f.show(all,List.of(a),"light"));assertSame(f.rows,second.getParent());assertFalse(second.isVisible());
                assertFalse(f.show(all,List.of(b),"light"));assertSame(f.rows,first.getParent());assertFalse(first.isVisible());
                assertFalse(f.show(all,all,"light"));
            }
            assertEquals(2,f.builds);assertSame(first,f.panels.get(a.key()));assertSame(html,label.getClientProperty(BasicHTML.propertyKey));
            assertEquals("invalid draft",field.getText());assertEquals(2,field.getSelectionStart());assertEquals(5,field.getSelectionEnd());
        });
    }
    @Test void filteredRowsAreExcludedFromAgentDiscoveryAndEmptyResultsRecover()throws Exception{
        SwingUtilities.invokeAndWait(()->{
            var f=new Fixture();var a=setting("font.size","16");var b=setting("other","14");var all=List.of(a,b);f.show(all,all,"light");
            f.show(all,List.of(a),"light");var targets=new ArrayList<AgentUiTarget>();AgentUiTargets.collect(f.rows,"settings",AgentUiPermission.APPEARANCE,targets);
            assertEquals(List.of("settings/font.size"),targets.stream().map(AgentUiTarget::id).toList());
            f.show(all,List.of(),"light");targets.clear();AgentUiTargets.collect(f.rows,"settings",AgentUiPermission.APPEARANCE,targets);assertTrue(targets.isEmpty());
            assertTrue(Arrays.stream(f.rows.getComponents()).anyMatch(c->c instanceof JLabel label&&label.isVisible()&&label.getText().startsWith("No settings")));
            f.show(all,all,"light");assertEquals(2,f.builds);assertTrue(f.panels.values().stream().allMatch(Component::isVisible));
        });
    }
    @Test void savedValuesMetadataAndExplicitResetReplaceOnlyTheChangedRow()throws Exception{
        SwingUtilities.invokeAndWait(()->{
            var f=new Fixture();var a=setting("font.size","16");var b=setting("other","14");f.show(List.of(a,b),List.of(a,b),"light");var other=f.panels.get(b.key());
            var changed=setting(a.key(),"18");f.show(List.of(changed,b),List.of(changed,b),"light");assertEquals(3,f.builds);assertSame(other,f.panels.get(b.key()));
            assertEquals("18",((JTextField)f.panels.get(a.key()).getComponent(0)).getText());
            f.rows.invalidate(a.key());f.show(List.of(changed,b),List.of(changed,b),"light");assertEquals(4,f.builds);
            assertFalse(f.show(List.of(changed,b),List.of(changed,b),"light"));assertEquals(4,f.builds);
            var metadata=new SettingsApplication.Setting(a.key(),"Editor","integer","18","12","New description","integer","Restart");
            f.show(List.of(metadata,b),List.of(metadata,b),"light");assertEquals(5,f.builds);assertSame(other,f.panels.get(b.key()));
            assertTrue(((JLabel)f.panels.get(a.key()).getComponent(1)).getText().contains("New description"));
        });
    }
    @Test void appearanceChangesRefreshHiddenRowsWhenRevealedAndRemovedSettingsAreReleased()throws Exception{
        SwingUtilities.invokeAndWait(()->{
            var f=new Fixture();var a=setting("font.size","16");var b=setting("other","14");var all=List.of(a,b);f.show(all,all,"light");
            var first=f.panels.get(a.key());var second=f.panels.get(b.key());f.show(all,List.of(a),"dark/zoom2");assertNotSame(first,f.panels.get(a.key()));assertSame(second,f.panels.get(b.key()));
            f.show(all,all,"dark/zoom2");assertNotSame(second,f.panels.get(b.key()));assertNull(second.getParent());assertEquals(4,f.builds);
            var removed=f.panels.get(b.key());f.show(List.of(a),List.of(a),"dark/zoom2");assertNull(removed.getParent());assertEquals(3,f.rows.getComponentCount());
            assertTrue(f.rows.getScrollableTracksViewportWidth());assertFalse(f.rows.getScrollableTracksViewportHeight());
            assertFalse(f.show(List.of(a),List.of(a),"dark/zoom2"));
        });
    }
    @Test void filteredLayoutShrinksAndKeepsResultOrderWithoutRebuilding()throws Exception{
        SwingUtilities.invokeAndWait(()->{
            var f=new Fixture();var a=setting("first","16");var b=setting("second","18");var all=List.of(a,b);f.show(all,all,"light");
            int height=f.rows.getPreferredSize().height;f.show(all,List.of(b),"light");assertTrue(f.rows.getPreferredSize().height<height);
            var layout=(GridBagLayout)f.rows.getLayout();assertEquals(0,layout.getConstraints(f.panels.get(b.key())).gridy);
            f.show(all,List.of(b,a),"light");assertEquals(0,layout.getConstraints(f.panels.get(b.key())).gridy);assertEquals(1,layout.getConstraints(f.panels.get(a.key())).gridy);assertEquals(2,f.builds);
            f.rows.invalidate(a.key());f.show(all,List.of(b),"light");assertEquals(2,f.builds);f.show(all,all,"light");assertEquals(3,f.builds);
            assertFalse(f.show(all,all,"light"));
        });
    }
    @Test void emptyResultsStillReportAppearanceChanges()throws Exception{
        SwingUtilities.invokeAndWait(()->{
            var f=new Fixture();assertTrue(f.show(List.of(),List.of(),"light"));assertFalse(f.show(List.of(),List.of(),"light"));
            assertTrue(f.show(List.of(),List.of(),"dark"));assertEquals(0,f.builds);
        });
    }
}
