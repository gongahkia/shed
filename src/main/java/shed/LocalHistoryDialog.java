package shed;

import java.awt.*;
import java.nio.file.Path;
import java.time.Instant;
import javax.swing.*;

/** Saved versions are observations; restoration is a checked, undoable buffer edit. */
final class LocalHistoryDialog extends JDialog {
    private final Texteditor editor;
    private final FileBuffer source;
    private final Path path;
    private final DefaultListModel<LocalHistoryStore.Version> versions=new DefaultListModel<>();
    private final JList<LocalHistoryStore.Version> list=new JList<>(versions);
    private final JTextArea preview=new JTextArea();
    private final JLabel state=new JLabel("Loading saved versions…");
    private final JButton restore=new JButton("Restore to buffer"),compare=new JButton("Compare with buffer");
    private long generation,revision;
    private String selected;
    private int job=-1;
    static void showFor(Texteditor editor){
        var buffer=editor.getCurrentBuffer();
        if(buffer==null||!buffer.hasFilePath()||buffer.isLargeFile()){editor.showMessage("Local history requires a regular file up to 512 Ki UTF-16 units.");return;}
        new LocalHistoryDialog(editor,buffer).setVisible(true);
    }
    private LocalHistoryDialog(Texteditor editor,FileBuffer source){
        super(editor,"Local History · "+source.getDisplayName(),false);this.editor=editor;this.source=source;path=source.getFile().toPath();
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);KeyboardFocusSupport.installEscape(getRootPane(),this::dispose);
        preview.setEditable(false);preview.setFont(editor.resolveEditorFont());preview.setName("history.preview");list.setName("history.versions");
        AccessibilitySupport.describe(list,"Saved versions","Select a saved version to inspect it. Restore edits the buffer and can be undone; save separately.");
        list.setCellRenderer(new DefaultListCellRenderer(){public Component getListCellRendererComponent(JList<?> l,Object value,int index,boolean chosen,boolean focus){var label=(JLabel)super.getListCellRendererComponent(l,value,index,chosen,focus);label.putClientProperty("html.disable",true);if(value instanceof LocalHistoryStore.Version v)label.setText(Instant.ofEpochMilli(v.millis())+" · "+v.units()+" units");return label;}});
        var split=new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,new JScrollPane(list),new JScrollPane(preview));split.setResizeWeight(.3);add(split);
        var actions=new JPanel(new FlowLayout(FlowLayout.TRAILING));var clear=new JButton("Delete saved versions…");clear.addActionListener(e->clear());actions.add(clear);actions.add(compare);actions.add(restore);
        var footer=new JPanel(new BorderLayout());footer.add(state);footer.add(actions,BorderLayout.SOUTH);add(footer,BorderLayout.SOUTH);
        list.addListSelectionListener(e->{if(!e.getValueIsAdjusting())read();});restore.addActionListener(e->restore());compare.addActionListener(e->compare());restore.setEnabled(false);compare.setEnabled(false);
        editor.editorUiController.prepareDialog(this,860,560);pack();setLocationRelativeTo(editor);load();
    }
    private boolean current(){return !editor.closingDown&&editor.buffers.contains(source)&&editor.getCurrentBuffer()==source&&source.getFile()!=null&&source.getFile().toPath().equals(path)&&!editor.agentOwnsEditor()&&editor.getActivePane().surface().editable();}
    private void load(){
        long request=++generation;job=editor.asyncJobService.submit("Local history",token->editor.localHistoryStore().versions(path),(j,result,error)->{
            if(request!=generation||!isDisplayable())return;
            versions.clear();if(error!=null){state.setText(error.getMessage());return;}for(var v:result)versions.addElement(v);
            state.setText(result.isEmpty()?"No saved versions yet. Save this file in Shed to begin.":"Restore changes the buffer only. Undo restores your current draft.");
            if(!source.localHistoryError().isBlank())state.setText("Last history capture: "+source.localHistoryError());
        });
    }
    private void read(){
        selected=null;restore.setEnabled(false);compare.setEnabled(false);var value=list.getSelectedValue();if(value==null)return;
        if(job>=0)editor.asyncJobService.cancel(job);long request=++generation;revision=source.documentRevision();
        job=editor.asyncJobService.submit("Read saved version",token->editor.localHistoryStore().read(path,value.id()),(j,text,error)->{
            if(request!=generation||!isDisplayable())return;if(error!=null){state.setText(error.getMessage());return;}
            selected=text;preview.setText(text);preview.setCaretPosition(0);restore.setEnabled(true);compare.setEnabled(true);
        });
    }
    private boolean validateContext(){if(selected==null||!current()||source.documentRevision()!=revision){state.setText("Editor context changed. Select a saved version again before continuing.");restore.setEnabled(false);compare.setEnabled(false);return false;}return true;}
    private void restore(){
        if(!validateContext())return;
        if(JOptionPane.showConfirmDialog(this,"Replace the current buffer with this saved version? You can Undo. Disk changes only when you save.","Restore saved version",JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION)return;
        if(!validateContext())return;
        restore(source,revision,selected);editor.markModified();dispose();editor.requestActivePaneFocus();
    }
    static void restore(FileBuffer source,long revision,String text){
        if(!SwingUtilities.isEventDispatchThread()||source.documentRevision()!=revision||source.isLargeFile()||text.length()>LocalHistoryStore.MAX_UNITS)throw new IllegalStateException("History restore context changed");
        var undo=(BoundedUndoManager)source.getUndoManager();undo.beginCompoundEdit();try{source.getDocument().remove(0,source.getDocument().getLength());source.getDocument().insertString(0,text,null);undo.endCompoundEdit();}catch(javax.swing.text.BadLocationException|RuntimeException error){undo.cancelCompoundEdit();throw new IllegalStateException(error);}
    }
    private void compare(){
        if(!validateContext())return;String before=selected,after=source.getContent();long expected=revision;long request=++generation;
        job=editor.asyncJobService.submit("Compare saved version",token->{var patch=new StringBuilder("Saved version → current buffer\n");for(var h:WorkspaceTextEdits.diff(before,after)){patch.append("@@ UTF-16 ").append(h.start()).append(" @@\n");for(String line:h.before().split("\n",-1))patch.append('-').append(line).append('\n');for(String line:h.replacement().split("\n",-1))patch.append('+').append(line).append('\n');if(patch.length()>100000)break;}return patch.toString();},(j,patch,error)->{if(request!=generation||!isDisplayable())return;if(error!=null){state.setText(error.getMessage());return;}if(current()&&source.documentRevision()==expected){editor.showComparison("Local History · "+source.getDisplayName(),patch);dispose();}else state.setText("Editor changed. Select the version again.");});
    }
    private void clear(){if(JOptionPane.showConfirmDialog(this,"Delete retained versions for this file? This cannot be undone.","Delete local history",JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION)return;long request=++generation;job=editor.asyncJobService.submit("Delete local history",token->{editor.localHistoryStore().clear(path);return null;},(j,v,error)->{if(request!=generation||!isDisplayable())return;if(error!=null)state.setText(error.getMessage());else load();});}
    @Override public void dispose(){generation++;if(job>=0)editor.asyncJobService.cancel(job);super.dispose();}
}
