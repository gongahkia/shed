package shed;

import java.nio.file.*;
import java.util.*;
import javax.swing.*;

/** A small task dialog over the existing attachment, watcher and workbench lifecycle. */
final class AgentTaskLauncher {
    private AgentTaskLauncher(){}
    static void show(Texteditor editor) {
        var root=editor.workspaceController.activeRoot();var delegation=editor.agentDelegation();
        if(root==null){editor.showMessage("Open a project before delegating a task.");return;}
        if(delegation.lease.running()){editor.showMessage("A delegation request is already running. Take back control first.");return;}
        var provider=new JComboBox<>(new String[]{"Codex CLI","Claude Code CLI","Other CLI (task on stdin)"});
        provider.setRenderer((list,value,index,selected,focus)->{var label=(JLabel)new DefaultListCellRenderer().getListCellRendererComponent(list,value,index,selected,focus);label.putClientProperty("html.disable",true);label.setIcon(ProviderIcons.forValue(value,label.getBackground()));return label;});
        var command=new JTextField("[\"codex\",\"exec\",\"--sandbox\",\"workspace-write\",\"-\"]",48);command.setName("agent.launchCommand");
        provider.addActionListener(e->{command.setText(provider.getSelectedIndex()==0?"[\"codex\",\"exec\",\"--sandbox\",\"workspace-write\",\"-\"]":provider.getSelectedIndex()==1?"[\"claude\",\"-p\"]":"[\"/path/to/agent-cli\"]");});
        var name=new JTextField("Codex",16);name.setName("agent.launchName");provider.addActionListener(e->name.setText(provider.getSelectedIndex()==0?"Codex":provider.getSelectedIndex()==1?"Claude":"Agent"));
        var task=new JTextArea(7,52);task.setFont(UIManager.getFont("Label.font"));task.setLineWrap(true);task.setWrapStyleWord(true);task.setName("agent.task");
        var panel=new JPanel();panel.setLayout(new BoxLayout(panel,BoxLayout.Y_AXIS));
        panel.add(new JLabel("Project: "+root));panel.add(Box.createVerticalStrut(8));panel.add(provider);panel.add(new JLabel("Display name"));panel.add(name);panel.add(new JLabel("CLI argument array · task is sent on standard input"));panel.add(command);panel.add(Box.createVerticalStrut(8));panel.add(new JLabel("Task"));panel.add(new JScrollPane(task));
        var note=new JTextArea("Uses this CLI's existing login and tool permissions. No permission bypass flags are added. You will approve window control before the CLI starts.");note.setFont(UIManager.getFont("Label.font"));note.setEditable(false);note.setOpaque(false);note.setLineWrap(true);note.setWrapStyleWord(true);panel.add(Box.createVerticalStrut(8));panel.add(note);
        for(java.awt.Component child:panel.getComponents())if(child instanceof JComponent component)component.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
        while(JOptionPane.showConfirmDialog(editor,panel,"Delegate Task",JOptionPane.OK_CANCEL_OPTION,JOptionPane.PLAIN_MESSAGE)==JOptionPane.OK_OPTION){
            try {
                var argv=ManagedAgentTask.command(command.getText());String description=task.getText(),agent=name.getText();
                String prompt=AgentTaskInstructions.prompt(description,agent),id=UUID.randomUUID().toString();
                // Validate before starting a listener or changing the current permission lease.
                var validation=new AgentDelegation();validation.request(id,agent,description);
                delegation.requireApproval();editor.handleAgentCommand("api start control");
                var job=new java.util.concurrent.atomic.AtomicInteger(-1);
                delegation.launchDescription(MiniJson.stringify(argv));
                delegation.whenActive(()->{
                    var server=editor.visualAttachment();if(server==null)throw new IllegalStateException("Attachment unavailable");
                    Path descriptor=server.descriptor();
                    job.set(editor.asyncJobService.submit("Agent task · "+agent,token->{
                        return ManagedAgentTask.run(argv,root,descriptor,id,prompt,null,()->!token.isCancelled()&&delegation.lease.allowed(id),8*60_000);
                    },(snapshot,result,error)->{
                        if(!delegation.lease.allowed(id))return;
                        if(error!=null||result==null){delegation.lease.revoke("failed");delegation.takeBack();editor.showMessage("Agent task failed; control returned to you. Check Tasks for details.");}
                        else delegation.finishTask(id,"CLI exit: "+result.exit()+"\n\nAgent-reported output:\n"+result.output(),result.exit()==0);
                    }));
                },()->{if(job.get()>=0)editor.asyncJobService.cancel(job.get());});
                delegation.command("request",id,agent,description);return;
            } catch(Exception invalid) {JOptionPane.showMessageDialog(editor,Objects.toString(invalid.getMessage(),"Could not start the selected CLI"),"Task setup",JOptionPane.WARNING_MESSAGE);}
        }
    }
}
