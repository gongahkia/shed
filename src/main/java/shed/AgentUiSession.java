package shed;

import java.awt.*;
import java.awt.event.*;
import java.awt.datatransfer.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import javax.swing.*;
import javax.swing.text.*;

/** One finite semantic interaction at a time on real Swing controls. No human-input arbitration. */
final class AgentUiSession implements AutoCloseable {
    interface Host {
        List<AgentUiTarget> targets();
        boolean permitted(AgentUiPermission permission);
        boolean current();
        boolean motion();
        default void check(AgentUiTarget target,String kind){}
        default void validate(AgentUiTarget target,AgentUiRequest request){}
        default String contextKey(){return "";}
        default boolean shortcut(AgentUiTarget target,String key){return false;}
        default void edit(AgentUiTarget target,int start,int end,String text) throws BadLocationException {
            var field=(JTextComponent)target.component();
            if(!field.isEditable())throw fail(WorkspaceApiException.Code.PERMISSION_DENIED);
            ((AbstractDocument)field.getDocument()).replace(start,end-start,text,null);
        }
        default void activity(AgentUiTarget target,String kind,int start,int end,String agent){}
        default JRootPane root(){return null;}
        default void invoke(AgentUiTarget target,String value){if(target.action()!=null)target.action().run(value);else if(target.component() instanceof AbstractButton button)button.doClick(0);else target.component().requestFocusInWindow();}
    }
    private final Host host;
    private final AgentUiPresentation presence;
    private long revision=1, sequence;
    private String fingerprint="", clipboard="";
    private Map<String,Object> last=Map.of("stage","idle");
    private javax.swing.Timer timer;
    private CompletableFuture<Map<String,Object>> pending;
    private boolean closed;
    private Runnable cleanup=()->{};
    AgentUiSession(Host host,AgentUiPresentation presence){this.host=host;this.presence=presence;}

    Map<String,Object> state(int offset){
        requireEdt();if(offset<0||offset>4096)throw fail(WorkspaceApiException.Code.INVALID_REQUEST);
        var targets=host.targets();if(offset>targets.size())throw fail(WorkspaceApiException.Code.INVALID_OFFSET);var stamp=new StringBuilder(host.contextKey());
        for(var target:targets){var c=target.component();if(c instanceof JTextComponent text)stamp.append(text.getCaretPosition()).append(text.getSelectionStart()).append(text.getSelectionEnd());stamp.append(target.id()).append(':').append(System.identityHashCode(c)).append(':').append(c.isShowing()).append(':').append(c.isEnabled());if(c instanceof JTextComponent text&&!Boolean.TRUE.equals(c.getClientProperty("shed.agent.source"))&&text.getDocument().getLength()<=AgentUiRequest.MAX_TEXT)stamp.append(text.getText()).append(text.getCaretPosition()).append(text.getSelectionStart());}
        String now=stamp.toString();if(!now.equals(fingerprint)){fingerprint=now;revision++;}
        var shown=new ArrayList<Map<String,Object>>();for(int i=offset;i<Math.min(targets.size(),offset+48);i++)shown.add(targets.get(i).metadata(host.permitted(targets.get(i).permission())));
        var m=new LinkedHashMap<String,Object>();m.put("kind","workbench");m.put("schemaVersion",2);m.put("revision",revision);m.put("targets",shown);
        m.put("offset",offset);m.put("nextOffset",Math.min(targets.size(),offset+shown.size()));m.put("total",targets.size());m.put("last",last);m.put("busy",pending!=null);
        return m;
    }
    Map<String,Object> inspect(String id,int offset){
        requireEdt();var target=target(id);check(target,"read");var m=new LinkedHashMap<String,Object>(target.metadata(true));
        m.put("items",target.items(offset));var scroll=AgentUiScroll.pane(target.component());if(scroll!=null)m.put("viewport",new AgentUiScroll(scroll,AgentUiScroll.Axis.VERTICAL,AgentUiScroll.Unit.PIXELS,0).state());
        return Map.of("kind","ui-target","schemaVersion",2,"revision",revision,"target",m);
    }
    private AgentUiTarget target(String id){return host.targets().stream().filter(t->t.id().equals(id)).findFirst().orElseThrow(()->fail(WorkspaceApiException.Code.BOUNDARY_UNAVAILABLE));}
    private void check(AgentUiTarget target,String kind){
        if(closed||!host.current())throw fail(WorkspaceApiException.Code.AUTHORIZATION_REVOKED);
        if(!host.permitted(target.permission()))throw fail(WorkspaceApiException.Code.PERMISSION_DENIED);
        if(!target.component().isShowing()||!target.component().isEnabled())throw fail(WorkspaceApiException.Code.BOUNDARY_UNAVAILABLE);
        host.check(target,kind);
    }
    CompletableFuture<Map<String,Object>> execute(AgentUiRequest request,BooleanSupplier authorized){
        requireEdt();state(0);
        if(pending!=null)throw fail(WorkspaceApiException.Code.BUSY);
        if(request.revision()!=revision)throw fail(WorkspaceApiException.Code.CONTEXT_CHANGED);
        var target=target(request.target());check(target,request.kind());host.validate(target,request);
        if(!authorized.getAsBoolean())throw fail(WorkspaceApiException.Code.AUTHORIZATION_REVOKED);
        var c=target.component();var completed=new CompletableFuture<Map<String,Object>>();
        long id=++sequence,started=System.nanoTime();int approach=host.motion()?240:0,duration=host.motion()?360:0;
        int index=request.args().containsKey("index")?(int)AgentUiRequest.integer(request.args(),"index"):-1;
        Point anchor=target.point(index);
        if(request.kind().equals("scroll")){var pane=AgentUiScroll.pane(c);if(pane==null)throw fail(WorkspaceApiException.Code.UNSUPPORTED);var viewport=pane.getViewport();presence.showAt(viewport,new Point(viewport.getWidth()/2,viewport.getHeight()/2),request.agent(),host.motion());}
        else presence.showAt(c,anchor,request.agent(),host.motion());
        last=Map.of("id",id,"stage","requested","agent",request.agent(),"operation",request.kind(),"target",target.id());
        AgentUiScroll scrolling=request.kind().equals("scroll")?new AgentUiScroll(AgentUiScroll.pane(c),AgentUiScroll.Axis.valueOf(request.args().get("axis").toString().toUpperCase(Locale.ROOT)),AgentUiScroll.Unit.valueOf(request.args().get("unit").toString().toUpperCase(Locale.ROOT)),(int)AgentUiRequest.integer(request.args(),"amount")):null;
        if(c instanceof JTextComponent text)boundedText(text);
        String[] desired={null};int[] written={0};int dragOrigin=c instanceof JSplitPane split?(int)(1000.0*split.getDividerLocation()/Math.max(1,(split.getOrientation()==JSplitPane.HORIZONTAL_SPLIT?split.getWidth():split.getHeight()))):c instanceof JScrollBar bar?(int)(1000.0*(bar.getValue()-bar.getMinimum())/Math.max(1,bar.getMaximum()-bar.getVisibleAmount()-bar.getMinimum())):-1;boolean[] applied={false};long[] finishAt={-1};Object[] highlight={null};Highlighter[] highlighter={null};
        cleanup=()->{if(highlight[0]!=null&&c instanceof JTextComponent text&&text.getHighlighter()==highlighter[0])highlighter[0].removeHighlight(highlight[0]);};
        pending=completed;
        timer=new javax.swing.Timer(16,event->{
            try{
                if(completed.isCancelled()||!authorized.getAsBoolean())throw fail(WorkspaceApiException.Code.CANCELLED);
                check(target,request.kind());
                long elapsed=(System.nanoTime()-started)/1_000_000;if(elapsed>3500)throw fail(WorkspaceApiException.Code.TIMEOUT);
                if(finishAt[0]>=0){presence.frame(1,false);if(elapsed-finishAt[0]>=(host.motion()?140:0))finish(id,request,target,Map.of(),completed,highlight,highlighter,null);return;}
                presence.frame(approach==0?1:Math.min(1f,(float)elapsed/approach),false);
                if(elapsed<approach)return;
                double phase=duration==0?1:Math.clamp((double)(elapsed-approach)/duration,0,1);
                if(scrolling!=null){presence.activity("scrolling",phase);scrolling.frame(phase);if(phase<1)return;finish(id,request,target,Map.of("viewport",scrolling.state()),completed,highlight,highlighter,null);return;}
                if(request.kind().equals("hover")){String tip=c.getToolTipText();finish(id,request,target,Map.of("tooltip",AgentUiTarget.bounded(tip,512)),completed,highlight,highlighter,null);return;}
                if(request.kind().equals("text")&&Set.of("set","insert").contains(request.args().get("mode"))){
                    var field=text(c);String value=AgentUiRequest.string(request.args(),"text",AgentUiRequest.MAX_TEXT);
                    if(desired[0]==null){
                        int a=request.args().get("mode").equals("set")?0:(int)AgentUiRequest.integer(request.args(),"start");
                        int b=request.args().get("mode").equals("set")?field.getDocument().getLength():(int)AgentUiRequest.integer(request.args(),"end");
                        bounds(field,a,b);desired[0]=value;
                        // source edits remain one checked transaction; fields show real progressive entry
                        if(Boolean.TRUE.equals(c.getClientProperty("shed.agent.source"))){host.edit(target,a,b,value);host.activity(target,"writing",a,a+value.length(),request.agent());applied[0]=true;}
                        else{host.edit(target,a,b,"");field.setCaretPosition(a);written[0]=a;}
                    }
                    if(!applied[0]){
                        int count=host.motion()&&value.chars().allMatch(ch->ch<128)?Math.min(value.length(),(int)((elapsed-approach)/Math.max(1,Math.min(14,650/Math.max(1,value.length()))))+1):value.length();
                        int already=field.getCaretPosition()-written[0];if(count>already)host.edit(target,field.getCaretPosition(),field.getCaretPosition(),value.substring(already,count));
                        field.setCaretPosition(written[0]+count);presence.frame(1,true);if(count<value.length())return;applied[0]=true;
                    }
                    revision++;finishAt[0]=elapsed;return;
                }
                if(request.kind().equals("drag")){drag(target,request,phase,dragOrigin);presence.anchor(target.point(-1));presence.frame(1,c instanceof JTextComponent);presence.activity("dragging",phase);if(phase<1)return;}
                else if(request.kind().equals("read")||request.kind().equals("text")&&Set.of("read","select").contains(request.args().get("mode"))){
                    var field=text(c);int a=(int)AgentUiRequest.integer(request.args(),"start"),b=(int)AgentUiRequest.integer(request.args(),"end");bounds(field,a,b);
                    if(b-a>AgentUiRequest.MAX_TEXT)throw fail(WorkspaceApiException.Code.LIMIT_EXCEEDED);
                    if(request.kind().equals("text")&&request.args().get("mode").equals("select")){field.select(a,b);presence.frame(1,true);}
                    else{
                        if(highlight[0]==null){highlighter[0]=field.getHighlighter();highlight[0]=highlighter[0].addHighlight(a,b,new DefaultHighlighter.DefaultHighlightPainter(new Color(42,139,176,65)));host.activity(target,"reading",a,b,request.agent());}
                        presence.activity("reading",phase);if(phase<1)return;
                        String content=field.getDocument().getText(a,b-a);finish(id,request,target,Map.of("content",content,"start",a,"end",b),completed,highlight,highlighter,null);return;
                    }
                }else if(request.kind().equals("text")){var field=text(c);int a=(int)AgentUiRequest.integer(request.args(),"start"),b=(int)AgentUiRequest.integer(request.args(),"end");bounds(field,a,b);host.edit(target,a,b,"");}
                else if(request.kind().equals("copy"))copy(target,false);
                else if(request.kind().equals("paste"))paste(target);
                else if(request.kind().equals("select")){select(c,index);}
                else if(request.kind().equals("key")){key(target,AgentUiRequest.string(request.args(),"key",64));}
                else {
                    // allow a modal controller to enter Swing's nested event loop without holding the request lease
                    last=Map.of("id",id,"stage","requested","agent",request.agent(),"operation",request.kind(),"target",target.id());
                    timer.stop();timer=null;pending=null;presence.settle();
                    SwingUtilities.invokeLater(()->{
                        try{
                            if(closed||!host.current())throw fail(WorkspaceApiException.Code.AUTHORIZATION_REVOKED);
                            check(target,request.kind());
                            if(request.kind().equals("action"))host.invoke(target,AgentUiRequest.string(request.args(),"value",4096));
                            else if(request.kind().equals("context")){select(c,index);Point p=target.point(index);var popup=c.getComponentPopupMenu();if(popup==null)throw fail(WorkspaceApiException.Code.UNSUPPORTED);popup.show(c,p.x,p.y);}
                            else if(request.kind().equals("double-click")){select(c,index);Point p=target.point(index);c.dispatchEvent(new MouseEvent(c,MouseEvent.MOUSE_CLICKED,System.currentTimeMillis(),0,p.x,p.y,2,false,MouseEvent.BUTTON1));}
                            else host.invoke(target,"");
                            revision++;last=event(id,request,"ui-updated",Map.of());painted(id,request,c);
                        }catch(RuntimeException failure){last=event(id,request,"failed",Map.of("code",failure instanceof WorkspaceApiException known?known.code().name():"INVALID_CONTEXT"));}
                    });
                    completed.complete(reply(id,request,"requested",Map.of()));return;
                }
                revision++;finish(id,request,target,Map.of(),completed,highlight,highlighter,null);
            }catch(Exception failed){finish(id,request,target,Map.of(),completed,highlight,highlighter,failed instanceof WorkspaceApiException known?known:fail(WorkspaceApiException.Code.INVALID_CONTEXT));}
        });timer.start();return completed;
    }
    private void finish(long id,AgentUiRequest request,AgentUiTarget target,Map<String,Object> details,CompletableFuture<Map<String,Object>> completed,Object[] tag,Highlighter[] painter,RuntimeException failure){
        if(timer!=null)timer.stop();timer=null;pending=null;presence.settle();
        cleanup.run();cleanup=()->{};
        if(failure!=null){last=event(id,request,"failed",Map.of("code",failure instanceof WorkspaceApiException known?known.code().name():"INVALID_CONTEXT"));completed.completeExceptionally(failure);return;}
        last=event(id,request,"ui-updated",Map.of());
        SwingUtilities.invokeLater(()->{if(closed){completed.completeExceptionally(fail(WorkspaceApiException.Code.CANCELLED));return;}String stage=painted(id,request,target.component());completed.complete(reply(id,request,stage,details));});
    }
    private String painted(long id,AgentUiRequest request,JComponent target){var root=host.root();if(root==null)root=SwingUtilities.getRootPane(target);String stage="ui-updated";if(root!=null&&root.isShowing()){root.paintImmediately(root.getVisibleRect());stage="painted";}last=event(id,request,stage,Map.of());return stage;}
    private Map<String,Object> reply(long id,AgentUiRequest request,String stage,Map<String,Object> details){var result=new LinkedHashMap<String,Object>(event(id,request,stage,details));result.put("kind","ui-result");result.put("schemaVersion",2);result.put("revision",revision);return result;}
    private static Map<String,Object> event(long id,AgentUiRequest request,String stage,Map<String,Object> details){var result=new LinkedHashMap<String,Object>();result.put("id",id);result.put("stage",stage);result.put("agent",request.agent());result.put("operation",request.kind());result.put("target",request.target());result.putAll(details);return result;}
    private static JTextComponent text(JComponent c){if(!(c instanceof JTextComponent field)||field instanceof JPasswordField)throw fail(WorkspaceApiException.Code.UNSUPPORTED);return field;}
    private static String boundedText(JTextComponent field){if(field.getDocument().getLength()>1_048_576)throw fail(WorkspaceApiException.Code.LIMIT_EXCEEDED);return "";}
    private static void bounds(JTextComponent field,int start,int end)throws BadLocationException{int length=field.getDocument().getLength();if(start<0||end<start||end>length)throw fail(WorkspaceApiException.Code.INVALID_OFFSET);if(length>1_048_576)throw fail(WorkspaceApiException.Code.LIMIT_EXCEEDED);String text=field.getDocument().getText(0,length);if(!GraphemeBoundary.isBoundary(text,start)||!GraphemeBoundary.isBoundary(text,end))throw fail(WorkspaceApiException.Code.INVALID_OFFSET);}
    private static void select(JComponent c,int index){
        if(c instanceof JList<?> list&&index<list.getModel().getSize()&&index>=0){list.setSelectedIndex(index);list.ensureIndexIsVisible(index);}
        else if(c instanceof JTable table&&index<table.getRowCount()&&index>=0){table.setRowSelectionInterval(index,index);table.scrollRectToVisible(table.getCellRect(index,0,true));}
        else if(c instanceof JTree tree&&index<tree.getRowCount()&&index>=0){tree.setSelectionRow(index);tree.scrollRowToVisible(index);}
        else if(c instanceof JComboBox<?> combo&&index<combo.getItemCount()&&index>=0)combo.setSelectedIndex(index);
        else if(c instanceof JTabbedPane tabs&&index<tabs.getTabCount()&&index>=0)tabs.setSelectedIndex(index);
        else throw fail(WorkspaceApiException.Code.INVALID_OFFSET);
    }
    private static void drag(AgentUiTarget target,AgentUiRequest r,double phase,int origin)throws BadLocationException{
        int a=origin>=0?origin:(int)AgentUiRequest.integer(r.args(),"start"),b=(int)AgentUiRequest.integer(r.args(),"end");double eased=1-Math.pow(1-phase,3);
        if(target.component() instanceof JSplitPane split){if(a>1000||b>1000)throw fail(WorkspaceApiException.Code.INVALID_OFFSET);split.setDividerLocation((a+(b-a)*eased)/1000);}
        else if(target.component() instanceof JScrollBar bar){if(a>1000||b>1000)throw fail(WorkspaceApiException.Code.INVALID_OFFSET);bar.setValue(bar.getMinimum()+(int)((bar.getMaximum()-bar.getVisibleAmount()-bar.getMinimum())*(a+(b-a)*eased)/1000));}
        else if(target.component() instanceof JTextComponent field){bounds(field,Math.min(a,b),Math.max(a,b));int end=a+(int)((b-a)*eased);if(end>0&&end<field.getDocument().getLength()){String value=field.getDocument().getText(Math.max(0,end-64),Math.min(128,field.getDocument().getLength()-Math.max(0,end-64)));int base=Math.max(0,end-64);end=base+GraphemeBoundary.floor(value,end-base);}field.setCaretPosition(a);field.moveCaretPosition(end);}
        else throw fail(WorkspaceApiException.Code.UNSUPPORTED);
    }
    private void copy(AgentUiTarget target,boolean cut)throws Exception{
        var field=text(target.component());bounds(field,field.getSelectionStart(),field.getSelectionEnd());
        if(field.getSelectionEnd()-field.getSelectionStart()>AgentUiRequest.MAX_TEXT)throw fail(WorkspaceApiException.Code.LIMIT_EXCEEDED);
        clipboard=field.getSelectedText()==null?"":field.getSelectedText();
        if(host.permitted(AgentUiPermission.SYSTEM_CLIPBOARD))Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(clipboard),null);
        if(cut)host.edit(target,field.getSelectionStart(),field.getSelectionEnd(),"");
    }
    private void paste(AgentUiTarget target)throws Exception{
        var field=text(target.component());String value=clipboard;
        if(host.permitted(AgentUiPermission.SYSTEM_CLIPBOARD)){Object data=Toolkit.getDefaultToolkit().getSystemClipboard().getData(DataFlavor.stringFlavor);value=data instanceof String s?s:"";}
        if(value.length()>AgentUiRequest.MAX_TEXT||!AgentUiActions.validUtf16(value))throw fail(WorkspaceApiException.Code.LIMIT_EXCEEDED);
        bounds(field,field.getSelectionStart(),field.getSelectionEnd());host.edit(target,field.getSelectionStart(),field.getSelectionEnd(),value);
    }
    private void key(AgentUiTarget target,String key)throws Exception{
        key=key.replace("mod ",System.getProperty("os.name","").startsWith("Mac")?"meta ":"control ");
        if(Set.of("meta C","control C","meta X","control X").contains(key)){copy(target,key.endsWith("X"));return;}
        if(Set.of("meta V","control V").contains(key)){paste(target);return;}
        if(host.shortcut(target,key))return;
        var c=target.component();if(!Set.of("ENTER","ESCAPE","TAB","shift TAB","UP","DOWN","LEFT","RIGHT","HOME","END","shift LEFT","shift RIGHT","shift UP","shift DOWN","control A","meta A","BACK_SPACE","DELETE").contains(key))throw fail(WorkspaceApiException.Code.UNSUPPORTED);KeyStroke stroke=KeyStroke.getKeyStroke(key);if(stroke==null)throw fail(WorkspaceApiException.Code.INVALID_REQUEST);
        if(stroke.getKeyCode()==KeyEvent.VK_TAB){if((stroke.getModifiers()&InputEvent.SHIFT_DOWN_MASK)!=0)c.transferFocusBackward();else c.transferFocus();return;}
        Object binding=null;Action action=null;for(Component owner=c;owner instanceof JComponent component;owner=owner.getParent()){binding=component.getInputMap(owner==c?JComponent.WHEN_FOCUSED:JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).get(stroke);if(binding!=null){action=component.getActionMap().get(binding);if(action!=null)break;}}if(action==null||!action.isEnabled())throw fail(WorkspaceApiException.Code.UNSUPPORTED);
        if(Boolean.TRUE.equals(c.getClientProperty("shed.agent.source"))&&(Set.of("cut-to-clipboard","paste-from-clipboard","delete-previous","delete-next","insert-break","insert-tab").contains(String.valueOf(binding))))throw fail(WorkspaceApiException.Code.UNSUPPORTED);
        action.actionPerformed(new ActionEvent(c,ActionEvent.ACTION_PERFORMED,String.valueOf(binding)));
    }
    boolean busy(){return pending!=null;}
    @Override public void close(){requireEdt();closed=true;cleanup.run();cleanup=()->{};if(timer!=null)timer.stop();timer=null;if(pending!=null)pending.completeExceptionally(fail(WorkspaceApiException.Code.CANCELLED));pending=null;clipboard="";presence.close();}
    private static WorkspaceApiException fail(WorkspaceApiException.Code code){return new WorkspaceApiException(code,"Workbench interaction unavailable: "+code);}
    private static void requireEdt(){if(!SwingUtilities.isEventDispatchThread())throw new IllegalStateException("Workbench interaction belongs on the EDT");}
}
