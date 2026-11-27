package shed;

import java.awt.*;
import java.nio.file.Path;
import java.util.*;
import javax.swing.*;
import static shed.ScmWorkbenchModel.*;

/** One source-control dock, with the existing Git workbench preserved as its Git page. */
final class SourceControlPanel implements ToolWindowHost.ToolSurface {
    private final Texteditor editor;
    private final JPanel panel=new JPanel(new BorderLayout(4,4)),pages=new JPanel(new CardLayout());
    private final JComboBox<Provider> providers=new JComboBox<>(Provider.values());
    private final GitChangesToolPanel git;
    private final ScmChangesPanel nativeScm;
    private boolean selecting,explicit,closed;
    private long generation;
    private int job=-1;
    private Path project;
    private long workspace=-1;
    SourceControlPanel(Texteditor editor,ToolWindowHost host){
        this.editor=editor;git=new GitChangesToolPanel(editor,host);nativeScm=new ScmChangesPanel(editor);
        var heading=new JPanel(new BorderLayout(6,0));var label=new JLabel("Version control");label.setLabelFor(providers);heading.add(label,BorderLayout.WEST);heading.add(providers);var detect=new JButton("Detect");detect.setToolTipText("Detect working copies for this project");detect.addActionListener(e->{explicit=false;refresh();});heading.add(detect,BorderLayout.EAST);panel.add(heading,BorderLayout.NORTH);
        providers.setName("scm.provider");AccessibilitySupport.describe(providers,"Version control provider","Git, Mercurial or Subversion. Each provider has its own working-copy actions.");
        pages.add(git.component(),"git");pages.add(nativeScm.component(),"native");panel.add(pages);
        providers.addActionListener(e->{if(selecting||closed)return;if(nativeScm.busy()){selecting=true;providers.setSelectedItem(currentProvider);selecting=false;editor.showMessage("Finish or Cancel the active source-control operation before switching providers.");return;}explicit=true;currentProvider=(Provider)providers.getSelectedItem();refresh();});
    }
    private Provider currentProvider=Provider.GIT;
    @Override public JPanel component(){return panel;}
    @Override public void focus(){if(currentProvider==Provider.GIT)git.focus();else nativeScm.focus();}
    @Override public void refreshAppearance(){git.refreshAppearance();nativeScm.refreshAppearance();}
    GitChangesToolPanel git(){
        if(nativeScm.busy()){editor.showMessage("Finish or Cancel the active source-control operation before switching providers.");return git;}
        explicit=true;currentProvider=Provider.GIT;selecting=true;providers.setSelectedItem(Provider.GIT);selecting=false;((CardLayout)pages.getLayout()).show(pages,"git");return git;
    }
    void select(Provider provider){if(nativeScm.busy()){editor.showMessage("Finish or Cancel the active source-control operation first.");return;}explicit=true;currentProvider=provider;selecting=true;providers.setSelectedItem(provider);selecting=false;refresh();}
    @Override public void refresh(){
        if(closed)return;Path next=editor.workspaceController.activeRoot();long version=editor.workspaceRoots.generation();
        if(!Objects.equals(project,next)||workspace!=version){explicit=false;project=next;workspace=version;nativeScm.context(currentProvider,null,next,version);}
        if(nativeScm.busy())return;if(job>=0)editor.asyncJobService.cancel(job);long request=++generation;Provider preferred=currentProvider;
        job=editor.asyncJobService.submit("Detect source control",token->{var roots=new EnumMap<Provider,Path>(Provider.class);for(var p:Provider.values()){if(token.isCancelled())break;try{Path root=discover(next,p);if(root!=null)roots.put(p,root);}catch(java.io.IOException invalid){if(p==preferred&&p!=Provider.GIT)throw invalid;}}return roots;},(j,roots,error)->{
            if(closed||request!=generation||workspace!=editor.workspaceRoots.generation()||!Objects.equals(project,editor.workspaceController.activeRoot()))return;job=-1;
            if(error!=null){nativeScm.context(preferred,null,project,workspace);((CardLayout)pages.getLayout()).show(pages,"native");editor.showMessage("Repository detection failed: "+error.getMessage());return;}
            Provider selected=explicit?currentProvider:roots.containsKey(Provider.GIT)?Provider.GIT:roots.containsKey(Provider.MERCURIAL)?Provider.MERCURIAL:roots.containsKey(Provider.SUBVERSION)?Provider.SUBVERSION:currentProvider;
            currentProvider=selected;selecting=true;providers.setSelectedItem(selected);selecting=false;
            if(selected==Provider.GIT){nativeScm.context(selected,null,project,workspace);((CardLayout)pages.getLayout()).show(pages,"git");git.refresh();}
            else {((CardLayout)pages.getLayout()).show(pages,"native");nativeScm.context(selected,roots.get(selected),project,workspace);}
        });
    }
    void observeExternalChanges(Path root,Set<String> recent){git.observeExternalChanges(root,recent);if(currentProvider!=Provider.GIT)nativeScm.observedChanges();}
    @Override public void close(){closed=true;generation++;if(job>=0)editor.asyncJobService.cancel(job);git.close();nativeScm.close();}
}
