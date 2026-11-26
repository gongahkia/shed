package shed;

import java.awt.*;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.*;
import javax.swing.*;

/** Thin chat frontend: native CLI sessions own conversation context; Shed supplies visualization context. */
final class AgentCliChatPanel implements ToolWindowHost.ToolSurface {
    record Attachment(Path root,Path descriptor,String id){}
    interface Host {
        Path root();
        void prepare(String agent,String message,JComponent surface,Consumer<Attachment> ready,Runnable ended);
        boolean current(Attachment attachment);
        void done(Attachment attachment);
        void stop();
        void changes();
    }
    interface Runner {
        ManagedAgentTask.Result run(List<String> command,Attachment attachment,String prompt,BooleanSupplier live,Consumer<String> chunk)throws Exception;
    }
    private final AsyncJobService jobs;
    private final Host host;
    private final Runner runner;
    private final Supplier<Font> font;
    private final JPanel panel=new JPanel(new BorderLayout(8,8));
    private final JComboBox<CliChatProtocol.Provider> providers=new JComboBox<>(CliChatProtocol.Provider.values());
    private final JTextField executable=new JTextField("codex",24);
    private final JTextArea transcript=new JTextArea(),composer=new JTextArea(3,30);
    private final JScrollPane conversation=new JScrollPane(transcript);
    private final JLabel status=new JLabel("Send a message to your local coding agent. Shed visualization is included automatically.");
    private final JButton send=new JButton("Send",WorkbenchIcons.PLAY),stop=new JButton("Stop",WorkbenchIcons.STOP),fresh=new JButton("New chat",WorkbenchIcons.ADD),changes=new JButton("Changes");
    private final StringBuilder history=new StringBuilder();
    private final javax.swing.Timer paint;
    private CliChatProtocol.Stream stream;
    private Attachment attachment;
    private Path boundRoot;
    private String nativeSession,reply="";
    private int job=-1;
    private long generation;
    private boolean active,closed,lostContext;
    private AtomicBoolean turnLive=new AtomicBoolean();

    AgentCliChatPanel(Texteditor editor) {
        this(editor.asyncJobService,new Host(){
            private String owned;
            public Path root(){return editor.workspaceController.activeRoot();}
            public void prepare(String agent,String message,JComponent surface,Consumer<Attachment> ready,Runnable ended){
                var d=editor.agentDelegation();
                if(owned==null||!d.lease.id().equals(owned)||!d.lease.state().equals("active"))owned=UUID.randomUUID().toString();
                String id=owned;editor.handleAgentCommand("api start control");
                d.chatTurn(id,agent,message,surface,()->{var server=editor.visualAttachment();if(server==null)throw new IllegalStateException("Shed attachment unavailable");ready.accept(new Attachment(root(),server.descriptor(),id));},ended);
            }
            public boolean current(Attachment a){return editor.agentDelegation().lease.id().equals(a.id())&&editor.agentDelegationAllowed(a.id());}
            public void done(Attachment a){editor.agentDelegation().chatTurnDone(a.id());}
            public void stop(){if(owned!=null&&editor.agentDelegation().lease.id().equals(owned))editor.takeBackAgentControl();}
            public void changes(){editor.toolWindowHost.showAgentChanges();}
        },(command,a,prompt,live,chunk)->ManagedAgentTask.run(command,a.root(),a.descriptor(),a.id(),prompt,null,live,8*60_000,chunk),editor::resolveUiFont);
    }
    AgentCliChatPanel(AsyncJobService jobs,Host host,Runner runner,Supplier<Font> font) {
        this.jobs=jobs;this.host=host;this.runner=runner;this.font=font;
        panel.setName("agent.chat");panel.setBorder(BorderFactory.createEmptyBorder(8,10,8,10));
        providers.setName("agent.chat.provider");executable.setName("agent.chat.executable");transcript.setName("agent.chat.transcript");composer.setName("agent.chat.composer");send.setName("agent.chat.send");stop.setName("agent.chat.stop");fresh.setName("agent.chat.new");status.setName("agent.chat.status");
        providers.setRenderer((list,value,index,selected,focus)->{var label=(JLabel)new DefaultListCellRenderer().getListCellRendererComponent(list,value,index,selected,focus);label.putClientProperty("html.disable",true);label.setIcon(ProviderIcons.forValue(value==null?"":value.title,label.getBackground()));return label;});
        providers.addActionListener(e->{if(nativeSession==null&&!active)executable.setText(provider().executable);});
        transcript.setEditable(false);transcript.setLineWrap(true);transcript.setWrapStyleWord(true);composer.setLineWrap(true);composer.setWrapStyleWord(true);status.putClientProperty("html.disable",true);
        var paths=new JPanel(new BorderLayout(6,0));paths.add(new JLabel("CLI executable"),BorderLayout.WEST);paths.add(executable);paths.setVisible(false);
        var customize=new JToggleButton("CLI path");customize.addActionListener(e->{paths.setVisible(customize.isSelected());panel.revalidate();});
        var controls=new JPanel(new FlowLayout(FlowLayout.LEADING,6,0));controls.add(providers);controls.add(fresh);controls.add(changes);controls.add(customize);
        var top=new JPanel(new BorderLayout(6,6));top.add(new JScrollPane(controls,JScrollPane.VERTICAL_SCROLLBAR_NEVER,JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED),BorderLayout.NORTH);top.add(paths,BorderLayout.SOUTH);panel.add(top,BorderLayout.NORTH);
        panel.add(conversation);
        var actions=new JPanel(new FlowLayout(FlowLayout.TRAILING,6,0));actions.add(stop);actions.add(send);
        var input=new JPanel(new BorderLayout(0,4));input.add(new JLabel("Message · Enter to send · Shift+Enter for a newline"),BorderLayout.NORTH);input.add(new JScrollPane(composer));
        var bottom=new JPanel(new BorderLayout(6,6));bottom.add(input);bottom.add(actions,BorderLayout.EAST);bottom.add(status,BorderLayout.SOUTH);panel.add(bottom,BorderLayout.SOUTH);
        send.addActionListener(e->send());stop.addActionListener(e->cancel());fresh.addActionListener(e->newChat());changes.addActionListener(e->host.changes());
        var submit=new AbstractAction(){public void actionPerformed(java.awt.event.ActionEvent e){send();}};
        composer.getInputMap().put(KeyStroke.getKeyStroke("ENTER"),"send");composer.getInputMap().put(KeyStroke.getKeyStroke("ctrl ENTER"),"send");composer.getInputMap().put(KeyStroke.getKeyStroke("meta ENTER"),"send");composer.getActionMap().put("send",submit);composer.getInputMap().put(KeyStroke.getKeyStroke("shift ENTER"),"insert-break");
        AccessibilitySupport.describe(composer,"Message your coding agent","Enter sends; Shift+Enter inserts a newline. Uses your CLI's existing login and permissions.");
        AccessibilitySupport.describe(transcript,"Agent conversation","Your messages and the agent's replies. Internal visualization instructions are supplied automatically.");
        paint=new javax.swing.Timer(60,e->flush());refreshAppearance();controls();
    }
    private CliChatProtocol.Provider provider(){return (CliChatProtocol.Provider)providers.getSelectedItem();}
    private void send(){
        if(closed||active||lostContext)return;
        String message=composer.getText();if(message.isBlank())return;
        if(message.length()>4096){status.setText("Keep this message within 4,096 characters.");return;}
        Path root=host.root();if(root==null){status.setText("Open a project before sending a message.");return;}
        if(boundRoot!=null&&!boundRoot.equals(root)){status.setText("Project changed. Start a new chat to choose a new project.");return;}
        final List<String> command;final String prompt;
        try{command=provider().command(executable.getText().trim(),nativeSession);prompt=AgentTaskInstructions.chatPrompt(message,provider().title);}
        catch(Exception failed){status.setText("Could not start CLI: "+Objects.toString(failed.getMessage(),"check CLI path and login"));return;}
        long request=++generation;active=true;turnLive=new AtomicBoolean(true);controls();status.setText("Preparing the conversation…");
        try {
            host.prepare(provider().title,message,panel,a->{
                if(closed||request!=generation)return;
                attachment=a;boundRoot=a.root();append("You\n"+message+"\n\n"+provider().title+"\n");if(composer.getText().equals(message))composer.setText("");reply="";
                var parsing=new CliChatProtocol.Stream(provider(),nativeSession);stream=parsing;var live=turnLive;paint.start();
                job=jobs.submit("Chat · "+provider().title,token->{
                    var result=runner.run(command,a,prompt,()->live.get()&&!token.isCancelled()&&host.current(a)&&parsing.snapshot().error().isEmpty(),parsing::accept);
                    parsing.finish();return result;
                },(snapshot,result,error)->{
                    if(closed||request!=generation)return;
                    flush();paint.stop();active=false;job=-1;
                    var update=parsing.snapshot();if(update.session()!=null)nativeSession=update.session();
                    if(update.session()==null){lostContext=true;}
                    String failure=!update.error().isEmpty()?update.error():error!=null?"Agent stopped or failed. Check the CLI's login, permissions and version.":result==null?"CLI returned no result.":result.exit()!=0?"CLI exited with code "+result.exit()+". Check its login, permissions and version.":update.session()==null?"CLI returned no conversation ID. Start a new chat after checking its version.":update.text().isBlank()?"CLI returned no supported reply; check its version and login.":null;
                    if(!reply.isEmpty())append(reply+"\n\n");reply="";stream=null;
                    status.setText(failure==null?"Ready · follow-up messages continue this conversation.":failure);
                    host.done(a);render();controls();
                });
            },()->ended(request));
        } catch(RuntimeException failed){active=false;turnLive.set(false);host.stop();status.setText(Objects.toString(failed.getMessage(),"Conversation unavailable"));controls();}
    }
    private void ended(long request){
        if(closed||request!=generation)return;
        flush();if(stream!=null&&stream.snapshot().session()!=null)nativeSession=stream.snapshot().session();
        ++generation;turnLive.set(false);if(job>=0)jobs.cancel(job);job=-1;active=false;paint.stop();
        if(!reply.isEmpty())append(reply+"\n\n");reply="";stream=null;attachment=null;render();status.setText(nativeSession==null?"Turn stopped before a conversation ID arrived; the next message starts a new session.":"Control ended. Send a follow-up to continue, or start a new chat.");controls();
    }
    private void flush(){if(stream==null)return;var update=stream.snapshot();if(!update.text().equals(reply)){reply=update.text();render();}if(active)status.setText(update.error().isEmpty()?update.status():update.error());}
    private void append(String text){history.append(text);if(history.length()>98304)history.delete(0,GraphemeBoundary.ceiling(history.toString(),history.length()-98304));}
    private void render(){var bar=conversation.getVerticalScrollBar();boolean follow=bar.getValue()+bar.getVisibleAmount()>=bar.getMaximum()-12;transcript.setText(history+reply);if(follow)transcript.setCaretPosition(transcript.getDocument().getLength());}
    private void controls(){send.setEnabled(!active&&!closed&&!lostContext);stop.setEnabled(active&&!closed);providers.setEnabled(!active&&nativeSession==null);executable.setEnabled(!active&&nativeSession==null);fresh.setEnabled(!closed);changes.setEnabled(!active&&!closed);}
    void stopRequests(){cancel();}
    private void cancel(){if(closed)return;long pending=generation;host.stop();if(generation==pending)ended(pending);}
    private void newChat(){cancel();nativeSession=null;lostContext=false;boundRoot=null;attachment=null;history.setLength(0);reply="";stream=null;composer.setText("");transcript.setText("");status.setText("New chat · send a message to begin.");controls();}
    @Override public JPanel component(){return panel;}
    @Override public void focus(){composer.requestFocusInWindow();}
    @Override public void refresh(){}
    @Override public void refreshAppearance(){for(var c:List.of(transcript,composer,providers,executable,status,send,stop,fresh,changes))c.setFont(font.get());}
    @Override public void close(){if(closed)return;host.stop();turnLive.set(false);++generation;if(job>=0)jobs.cancel(job);closed=true;paint.stop();stream=null;reply="";controls();}
}
