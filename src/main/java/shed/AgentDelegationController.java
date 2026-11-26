package shed;

import java.nio.file.Path;
import java.util.*;
import javax.swing.*;

/** Window-scoped consent and presentation for one bounded, explicitly approved session. */
final class AgentDelegationController implements AutoCloseable {
    final AgentDelegation lease=new AgentDelegation();
    private final Texteditor editor;
    private final javax.swing.Timer timer;
    private AgentEditorOwnership ownership;
    private JDialog approval;
    private Path root;
    private long generation;
    private Runnable onActive=()->{},onEnd=()->{};
    private boolean closed,handedBack;
    private String launchDescription="External CLI; Shed does not own its process.";
    void launchDescription(String value){launchDescription=value;}
    AgentDelegationController(Texteditor editor) {
        this.editor=editor;timer=new javax.swing.Timer(100,e->tick());
    }
    boolean ownsEditor(){return ownership!=null;}
    void requireApproval(){lease.requireApproval();}
    Map<String,Object> command(String action,String id,String agent,String task) {
        if(closed)throw EditorMetadata.failure(WorkspaceApiException.Code.APP_SHUTTING_DOWN);
        switch(action) {
            case "request" -> {
                if(lease.request(id,agent,task)) {
                    root=editor.workspaceController.activeRoot();generation=editor.workspaceRoots.generation();handedBack=false;
                    timer.start();SwingUtilities.invokeLater(()->ask(id));
                }
            }
            case "status" -> {}
            case "finish" -> { lease.finish(id,task);handBack(); }
            default -> throw WorkspaceApiException.invalidRequest("Unknown delegation action");
        }
        return lease.status(id);
    }
    void whenActive(Runnable active,Runnable ended){onActive=active;onEnd=ended;}
    private void ask(String id) {
        if(closed||!lease.id().equals(id)||!lease.state().equals("awaiting-approval"))return;
        if(root==null||!editor.canDelegateWindow()){lease.revoke("rejected");handBack();editor.showMessage("Finish pending saves and resolve unsaved buffers or reload prompts before delegating control.");return;}
        var body=new JPanel(new java.awt.BorderLayout(8,10));
        var task=new JTextArea(lease.task(),6,52);task.setFont(UIManager.getFont("Label.font"));task.setEditable(false);task.setLineWrap(true);task.setWrapStyleWord(true);task.setCaretPosition(0);body.add(new JScrollPane(task));
        var notice=new JTextArea("Allow "+lease.agent()+" to control this Shed window for up to 8 minutes?\nProject: "+root+"\nCLI: "+launchDescription+"\n\nTyping and navigation pause while the agent works. Take Back Control is always available.\nA fresh change baseline starts now. An external CLI keeps its own filesystem permissions.");
        notice.setFont(UIManager.getFont("Label.font"));notice.setEditable(false);notice.setLineWrap(true);notice.setWrapStyleWord(true);notice.setOpaque(false);body.add(notice,java.awt.BorderLayout.SOUTH);
        var pane=new JOptionPane(body,JOptionPane.QUESTION_MESSAGE,JOptionPane.YES_NO_OPTION,null,new String[]{"Allow Control","Cancel"},"Cancel");
        approval=pane.createDialog(editor,"Delegate control to "+lease.agent()+"?");approval.setName("agent.approval");
        approval.setModal(false);pane.addPropertyChangeListener(e->{
            if(!JOptionPane.VALUE_PROPERTY.equals(e.getPropertyName())||pane.getValue()==JOptionPane.UNINITIALIZED_VALUE)return;
            boolean allow="Allow Control".equals(pane.getValue());disposeApproval();
            if(!lease.id().equals(id)||!lease.state().equals("awaiting-approval"))return;
            if(!allow){lease.revoke("rejected");handBack();return;}
            if(!validProject()||!editor.canDelegateWindow()){lease.revoke("rejected");handBack();return;}
            editor.stopAiCompletionRequests();
            lease.approve(id);
            ownership=new AgentEditorOwnership(editor.getRootPane(),()->List.copyOf(editor.editorPanes),lease.agent(),this::takeBack);
            editor.toolWindowHost.watchAgentChanges("session-start",lease.agent());editor.hideToolWindow();
        });
        approval.addWindowListener(new java.awt.event.WindowAdapter(){public void windowClosing(java.awt.event.WindowEvent e){lease.revoke("rejected");handBack();}});
        approval.setVisible(true);
    }
    private boolean validProject(){return Objects.equals(root,editor.workspaceController.activeRoot())&&generation==editor.workspaceRoots.generation();}
    private void tick() {
        if(!validProject())lease.revoke("cancelled");
        String state=lease.state();
        if(state.equals("preparing")) {
            var watch=editor.toolWindowHost.watchAgentChanges("state",lease.agent());
            if(watch.get("error") instanceof String error&&!error.isEmpty())lease.revoke("failed");
            else if(editor.visualAttachmentFailed())lease.revoke("failed");
            else if(editor.visualAttachment()!=null&&Boolean.TRUE.equals(watch.get("ready"))){lease.activate(lease.id());editor.showMessage(lease.agent()+" has control · Take Back Control to return to editing.");try{onActive.run();}catch(RuntimeException failure){lease.revoke("failed");editor.showMessage("Agent task could not start; control returned to you.");}}
        }
        if(ownership!=null)ownership.refresh();
        if(!lease.running())handBack();
    }
    void finishTask(String id,String summary,boolean succeeded){lease.finish(id,summary,succeeded);handBack();}
    void takeBack(){lease.revoke("cancelled");handBack();}
    private void disposeApproval(){if(approval!=null){var dialog=approval;approval=null;dialog.dispose();}}
    private void handBack() {
        timer.stop();disposeApproval();
        boolean worked=ownership!=null;
        if(ownership!=null){ownership.close();ownership=null;}
        if(handedBack)return;handedBack=true;
        try{onEnd.run();}finally{onActive=()->{};onEnd=()->{};launchDescription="External CLI; Shed does not own its process.";}
        editor.clearAgentActivity();
        if(worked&&!closed){editor.toolWindowHost.watchAgentChanges("pause",lease.agent());editor.toolWindowHost.watchAgentChanges("refresh",lease.agent());editor.toolWindowHost.showAgentChanges();editor.showMessage(lease.agent()+" · "+lease.state()+" · control returned to you. Changes are available for review.");
            var status=lease.status(lease.id());if(status.get("summary") instanceof String summary&&!summary.isBlank()){var text=new JTextArea(summary,8,52);text.setEditable(false);text.setLineWrap(true);text.setWrapStyleWord(true);text.setCaretPosition(0);SwingUtilities.invokeLater(()->{if(!closed)JOptionPane.showMessageDialog(editor,new JScrollPane(text),"Agent-reported session summary",JOptionPane.INFORMATION_MESSAGE);});}}
    }
    @Override public void close(){closed=true;lease.revoke("cancelled");handBack();}
}
