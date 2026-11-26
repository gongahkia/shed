package shed;

import java.awt.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import javax.swing.*;

/** Controlled real Swing + external Java CLI double; no shell, cloud account or desktop automation. */
public final class AgentCliChatDesktopProbe {
    static Texteditor editor;static Path output,project;
    static <T>T ui(Callable<T> call)throws Exception{var task=new FutureTask<T>(call);SwingUtilities.invokeAndWait(task);return task.get(5,TimeUnit.SECONDS);}
    static void await(BooleanSupplier test)throws Exception{long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);while(!ui(test::getAsBoolean)){if(System.nanoTime()>until)throw new AssertionError("Chat condition timed out: "+find(editor.getRootPane(),"agent.chat.status"));Thread.sleep(25);}}
    static Component find(Component c,String name){if(name.equals(c.getName()))return c;if(c instanceof Container p)for(Component child:p.getComponents()){var found=find(child,name);if(found!=null)return found;}return null;}
    static JDialog approval(){for(Window w:Window.getWindows())if(w instanceof JDialog d&&d.isShowing()&&"agent.approval".equals(d.getName()))return d;return null;}
    static JButton button(Component c,String title){if(c instanceof JButton b&&title.equals(b.getText()))return b;if(c instanceof Container p)for(Component child:p.getComponents()){var found=button(child,title);if(found!=null)return found;}return null;}
    static javax.swing.text.JTextComponent transcript(){return (javax.swing.text.JTextComponent)find(editor.getRootPane(),"agent.chat.transcript");}
    static JTextArea composer(){return (JTextArea)find(editor.getRootPane(),"agent.chat.composer");}
    static JButton send(){return (JButton)find(editor.getRootPane(),"agent.chat.send");}
    static List<String> javaCommand(int turn){return List.of(Path.of(System.getProperty("java.home"),"bin/java").toString(),"-Djava.awt.headless=true","-Duser.home="+System.getProperty("user.home"),"-Djava.io.tmpdir="+System.getProperty("java.io.tmpdir"),"-cp",System.getProperty("java.class.path"),AgentCliChatDesktopProbe.class.getName(),"--fake-agent",Integer.toString(turn));}
    static void picture(String name)throws Exception{var image=ui(()->{var root=editor.getRootPane();return SwingComponentCapture.capture(root,0,0,root.getWidth(),root.getHeight(),8_000_000);});try{javax.imageio.ImageIO.write(image,"png",output.resolve(name+".png").toFile());}finally{image.flush();}}
    static void ctl(String...args)throws Exception{var argv=new ArrayList<String>(List.of("ctl"));argv.addAll(List.of(args));var out=new ByteArrayOutputStream();int exit=ShedControlCli.run(argv.toArray(String[]::new),InputStream.nullInputStream(),out,OutputStream.nullOutputStream());if(exit!=0)throw new AssertionError(out.toString(StandardCharsets.UTF_8));}
    static void fake(int turn)throws Exception{
        Path root=Path.of(".").toRealPath(),fixture=Path.of(System.getenv("SHED_PORTABLE_FIXTURE_ROOT")).toRealPath();if(!root.startsWith(fixture.resolve("run")))throw new AssertionError("Disposable project required");
        String prompt=new String(System.in.readNBytes(32769),StandardCharsets.UTF_8);if(!prompt.contains("SHED_CONTROL")||!prompt.contains("Do not call session-finish"))throw new AssertionError("Missing ongoing visualization context");
        System.out.println("{\"type\":\"thread.started\",\"thread_id\":\"fixture-chat\"}");System.out.flush();
        if(turn==3){Thread.sleep(30000);return;}
        if(turn==2&&(!prompt.contains("Add a follow-up")||!prompt.contains("\"text\":\"Initial\"")||!prompt.contains("\"endOffset\":7")))throw new AssertionError("Missing follow-up selection context");
        ctl("read","Demo.txt","--agent","Codex","--limit","2048");Thread.sleep("1".equals(System.getenv("SHED_DELEGATION_DEMO"))?1800:400);
        Path source=root.resolve("Demo.txt");Files.writeString(source,Files.readString(source)+"Change from turn "+turn+".\n");
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source)));ctl("sync","Demo.txt","--sha256",hash,"--timeout-ms","3000");
        System.out.println(MiniJson.stringify(Map.of("type","item.completed","item",Map.of("id","answer","type","agent_message","text",turn==1?"First reply: updated Demo.txt.":"Follow-up reply: continued the same conversation and updated Demo.txt again."))));
    }
    public static void main(String[] args)throws Exception{
        if(args.length==2&&args[0].equals("--fake-agent")){fake(Integer.parseInt(args[1]));return;}
        output=Path.of(args[0]).toRealPath();project=Path.of(args[1]).toRealPath();Path fixture=Path.of(System.getenv("SHED_PORTABLE_FIXTURE_ROOT")).toRealPath();if(!project.startsWith(fixture.resolve("run"))||!output.startsWith(fixture.resolve("run")))throw new AssertionError("Disposable fixture required");
        Runtime.getRuntime().addShutdownHook(new Thread(()->ProcessHandle.current().descendants().forEach(ProcessHandle::destroyForcibly)));
        Thread watchdog=Thread.ofPlatform().daemon().start(()->{try{Thread.sleep(100000);System.exit(124);}catch(InterruptedException ended){}});int exit=1;var events=new ArrayList<String>();
        try{
            Files.writeString(project.resolve("Demo.txt"),"Initial source.\n");editor=ui(()->new Texteditor(new String[]{project.toString()}));await(()->!editor.stagedStartupPending&&editor.isShowing());
            ui(()->{editor.setSize(1440,960);editor.workspaceController.addFolder(project,true);editor.handleAgentCommand("delegate");return null;});await(()->find(editor.getRootPane(),"agent.chat")!=null);
            picture("00-chat-layout");ui(()->{
                if(editor.editorToolSplit.getOrientation()!=JSplitPane.HORIZONTAL_SPLIT||find(editor.toolWindowHost,"workbench.tools")!=null)throw new AssertionError("Chat must be a single right-hand surface");
                for(int i=0;i<editor.getJMenuBar().getMenuCount();i++){var menu=editor.getJMenuBar().getMenu(i);if(menu!=null)for(var item:menu.getMenuComponents())if(item instanceof JMenuItem m&&"AI Assistant".equals(m.getText()))throw new AssertionError("Duplicate Assistant entry remains");}
                editor.toolWindowHost.showTab(ToolWindowHost.Tab.SEARCH);if(editor.editorToolSplit.getOrientation()!=JSplitPane.VERTICAL_SPLIT)throw new AssertionError("Other tools must retain bottom docking");
                editor.toolWindowHost.showAgentChat();editor.editorToolSplit.setDividerLocation(900);editor.validate();int divider=editor.editorToolSplit.getDividerLocation();
                editor.focusModeController.handleFocusCommand("on");if(editor.toolWindowHost.isVisible())throw new AssertionError("Focus mode did not hide chat");editor.focusModeController.handleFocusCommand("off");editor.validate();
                if(!editor.toolWindowHost.isVisible()||editor.editorToolSplit.getOrientation()!=JSplitPane.HORIZONTAL_SPLIT||Math.abs(divider-editor.editorToolSplit.getDividerLocation())>6)throw new AssertionError("Focus mode did not restore right chat width");
                return null;
            });events.add("One Chat entry docks right; other tools stay below; focus mode restores chat");
            ui(()->{editor.setSize(1100,820);((JComboBox<?>)find(editor.getRootPane(),"agent.chat.provider")).setSelectedItem(CliChatProtocol.Provider.CLAUDE);editor.validate();return null;});picture("00b-claude-narrow-chat");
            ui(()->{var dock=find(editor.getRootPane(),"agent.rightDock");var input=SwingUtilities.convertRectangle(composer().getParent(),composer().getBounds(),dock);if(input.x<0||input.x+input.width>dock.getWidth()||input.y<0||input.y+input.height>dock.getHeight())throw new AssertionError("Narrow chat composer is clipped");((JComboBox<?>)find(editor.getRootPane(),"agent.chat.provider")).setSelectedItem(CliChatProtocol.Provider.CODEX);editor.setSize(1440,960);editor.validate();return null;});events.add("Orange Claude picker and composer fit the narrow right-hand panel");
            var panelComponent=ui(()->find(editor.getRootPane(),"agent.chat"));Object panel=null;
            // Locate only this fixture window's own chat surface; no production test hook is exposed.
            var surfacesField=ToolWindowHost.class.getDeclaredField("surfaces");surfacesField.setAccessible(true);var surfaces=(Map<?,?>)surfacesField.get(editor.toolWindowHost);Object workbench=surfaces.get(ToolWindowHost.Tab.AGENT);
            ui(()->{var detach=ToolWindowHost.class.getDeclaredMethod("detach",ToolWindowHost.Tab.class,ToolWindowHost.ToolSurface.class);detach.setAccessible(true);detach.invoke(editor.toolWindowHost,ToolWindowHost.Tab.SEARCH,surfaces.get(ToolWindowHost.Tab.SEARCH));editor.toolWindowHost.showAgentChat();if(!editor.toolWindowHost.isSelected(ToolWindowHost.Tab.SEARCH)||!editor.toolWindowHost.isSelected(ToolWindowHost.Tab.AGENT))throw new AssertionError("Detached tool refresh lost while chat is docked");for(Window w:Window.getWindows())if(w instanceof JDialog d&&d.isShowing()&&"Search".equals(d.getTitle()))d.dispose();return null;});
            await(()->editor.editorToolSplit.getOrientation()==JSplitPane.VERTICAL_SPLIT);ui(()->{editor.toolWindowHost.showAgentChat();return null;});events.add("Detached search stays active beside chat and reattaches to its bottom dock");
            var chatField=AgentWorkbenchPanel.class.getDeclaredField("chat");chatField.setAccessible(true);panel=chatField.get(workbench);
            var agents=(AgentWorkbenchPanel)workbench;ui(()->{for(int i=0;i<2;i++){agents.showReview();((JButton)find(editor.getRootPane(),"agent.backToChat")).doClick();agents.showLiveChanges();((JButton)find(editor.getRootPane(),"agent.backToChat")).doClick();}return null;});
            var turns=new java.util.concurrent.atomic.AtomicInteger();var runnerField=AgentCliChatPanel.class.getDeclaredField("runner");runnerField.setAccessible(true);
            runnerField.set(panel,(AgentCliChatPanel.Runner)(command,a,prompt,live,chunk)->{int turn=turns.incrementAndGet();if(turn==2&&!command.containsAll(List.of("resume","fixture-chat")))throw new AssertionError("Follow-up did not resume native context");var result=ManagedAgentTask.run(javaCommand(turn),a.root(),a.descriptor(),a.id(),prompt,new HashMap<>(System.getenv()),live,15000,chunk);Files.writeString(output.resolve("turn-"+turn+".log"),result.output());return result;});
            ui(()->{((JTextField)find(editor.getRootPane(),"agent.chat.executable")).setText(AgentTaskInstructions.cli().getFirst());composer().setText("Update Demo.txt");send().doClick();return null;});await(()->approval()!=null);
            ui(()->{button(approval(),"Allow Control").doClick();return null;});await(()->"Agent reading".equals(editor.writingArea.getClientProperty("shed.agentActivity")));
            ui(()->{if(!composer().isEditable()||editor.writingArea.isEditable())throw new AssertionError("Chat/source input ownership is incorrect");var shield=editor.getGlassPane();Point point=SwingUtilities.convertPoint(composer(),5,5,shield);if(shield.contains(point))throw new AssertionError("Glass pane blocks chat input");var close=find(editor.getRootPane(),"agent.dock.close");if(shield.contains(SwingUtilities.convertPoint(close,5,5,shield)))throw new AssertionError("Glass pane blocks chat close");return null;});picture("01-chat-and-reading");events.add("Chat input and close stay usable while real editor reads are highlighted");
            await(()->transcript().getText().contains("First reply")&&send().isEnabled());ui(()->{if(!editor.writingArea.isEditable())throw new AssertionError("Human editor was not restored between messages");var lease=editor.agentDelegation().lease;if(!lease.state().equals("active")||editor.agentDelegationAllowed(lease.id()))throw new AssertionError("Bridge must be suspended between chat turns");return null;});
            var server=ui(()->editor.visualAttachment());String leaseId=ui(()->editor.agentDelegation().lease.id());try(var client=WorkspaceBridgeClient.control(server.descriptor(),server.credential())){client.delegation(leaseId);var response=MiniJson.asObject(MiniJson.parseStrict(client.exchange(client.navigationContextRequest())));var error=MiniJson.asObject(response.get("error"));if(error==null||!"AUTHORIZATION_REVOKED".equals(MiniJson.asObject(error.get("data")).get("code")))throw new AssertionError("Idle chat bridge admitted a request");}events.add("Authenticated bridge rejects source access between chat turns");
            ui(()->{
                var area=editor.writingArea;var buffer=editor.getActivePane().getBuffer();String before=area.getText();long revision=buffer.documentRevision();boolean undo=buffer.getUndoManager().canUndo();area.select(0,7);
                var menu=area.getComponentPopupMenu();for(var listener:menu.getPopupMenuListeners())listener.popupMenuWillBecomeVisible(new javax.swing.event.PopupMenuEvent(menu));
                var add=(JMenuItem)find(menu,"editor.addSelectionToChat");if(add==null||!add.isEnabled())throw new AssertionError("Selection action missing");add.doClick();
                if(find(editor.getRootPane(),"agent.chat.removeContext")==null||turns.get()!=1)throw new AssertionError("Selection must attach without sending");
                if(!area.getText().equals(before)||buffer.documentRevision()!=revision||buffer.getUndoManager().canUndo()!=undo||area.getSelectionStart()!=0||area.getSelectionEnd()!=7)throw new AssertionError("Adding context changed source/history/selection");
                return null;
            });picture("01b-selection-context");events.add("Selected code attaches through the editor menu without sending or source/history mutation");
            ui(()->{composer().setText("Add a follow-up change");send().doClick();return null;});
            await(()->transcript().getText().contains("Follow-up reply")&&send().isEnabled());String expected="Initial source.\nChange from turn 1.\nChange from turn 2.\n";if(!Files.readString(project.resolve("Demo.txt")).equals(expected))throw new AssertionError("Source changed unexpectedly");await(()->editor.writingArea.getText().equals(expected)&&!editor.getActivePane().getBuffer().isModified());
            ui(()->{if(transcript().getText().contains("SHED_CONTROL"))throw new AssertionError("Bootstrap leaked into visible chat");return null;});picture("02-follow-up-and-source");events.add("Two turns use one native conversation with fresh hidden visualization instructions");
            if("1".equals(System.getenv("SHED_DELEGATION_DEMO")))Thread.sleep(1800);
            ui(()->{
                editor.hideToolWindow();if(editor.toolWindowHost.isVisible())throw new AssertionError("Chat close failed");editor.toolWindowHost.showAgentChat();
                var list=(JList<?>)find(editor.getRootPane(),"workbench.openEditors");int selected=list.getSelectedIndex(),index=-1;for(int i=0;i<list.getModel().getSize();i++)if(i!=selected){index=i;break;}if(index<0)throw new AssertionError("Need inactive tab fixture");
                int count=list.getModel().getSize();var active=editor.documents.activeDocument().id();var cell=list.getCellBounds(index,index);
                list.dispatchEvent(new java.awt.event.MouseEvent(list,java.awt.event.MouseEvent.MOUSE_PRESSED,System.currentTimeMillis(),0,cell.x+cell.width-12,cell.y+cell.height/2,1,false,java.awt.event.MouseEvent.BUTTON1));
                if(list.getModel().getSize()!=count-1||!editor.documents.activeDocument().id().equals(active))throw new AssertionError("Inline close affected wrong tab");
                return null;
            });events.add("Chat hide/reopen preserves conversation; inline close targets its inactive tab");
            ui(()->{composer().setText("Wait for my next message");send().doClick();return null;});await(()->turns.get()==3&&ManagedAgentTask.activeIoWorkers()>0);
            ui(()->{composer().setText("Retain this next draft");((JButton)find(editor.getRootPane(),"agent.chat.stop")).doClick();return null;});await(()->ManagedAgentTask.activeIoWorkers()==0&&ProcessHandle.current().descendants().noneMatch(ProcessHandle::isAlive));
            ui(()->{if(!composer().getText().equals("Retain this next draft")||!editor.writingArea.isEditable())throw new AssertionError("Stop lost input or editor ownership");((JButton)find(editor.getRootPane(),"agent.chat.new")).doClick();if(!transcript().getText().isBlank())throw new AssertionError("New chat did not reset transcript");return null;});events.add("Stop releases processes and source ownership; New chat resets conversation");
            var samples=new ArrayList<Double>();for(int i=0;i<40;i++)samples.add(ui(()->{long start=System.nanoTime();editor.writingArea.append("x");editor.writingArea.paintImmediately(editor.writingArea.getVisibleRect());return (System.nanoTime()-start)/1e6;}));var sorted=new ArrayList<>(samples);Collections.sort(sorted);double p95=sorted.get(37);if(p95>16)throw new AssertionError("Typing p95 exceeded 16 ms: "+p95);
            Files.writeString(output.resolve("result.json"),MiniJson.stringify(Map.of("state","PASS","events",events,"turns",turns.get(),"remaining_task_io_workers",ManagedAgentTask.activeIoWorkers(),"model","FIXED_JAVA_CLI_DOUBLE","native_shell","NOT_USED","post_chat_completed_paint",Map.of("first_ms",samples.getFirst(),"p95_ms",p95,"samples_ms",samples))));exit=0;
        }catch(Throwable error){error.printStackTrace();Files.writeString(output.resolve("result.json"),MiniJson.stringify(Map.of("state","FAIL","error",error.toString(),"events",events)));}
        finally{if(editor!=null)ui(()->{editor.agentDelegation().close();editor.handleAgentCommand("api stop");editor.asyncJobService.shutdownNow();for(Window w:Window.getWindows())w.dispose();return null;});watchdog.interrupt();System.exit(exit);}
    }
}
