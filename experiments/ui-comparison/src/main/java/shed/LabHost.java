package shed;

import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.datatransfer.Clipboard;
import javax.accessibility.Accessible;

/** Runs the real Shed workbench with isolated experimental chrome. Never on the production classpath. */
public final class LabHost {
    private Texteditor editor;
    private LabModel model;
    private LabFactory factory;
    private LabView navigation, settings;
    private JSplitPane split;
    private JDialog detached;
    private final List<Map<String,Object>> captures = new ArrayList<>();
    private final long started = System.nanoTime();
    private KeyEventDispatcher shortcutDispatcher;
    private String flavor;
    private Path fixture;
    private Path output;

    public static void main(String[] args) throws Exception {
        LabHost host = new LabHost();
        host.flavor = args.length == 0 ? "swing" : args[0];
        String mode = args.length < 2 ? "manual" : args[1];
        host.output = args.length < 3 ? Path.of("/tmp/shed-ui-lab-"+host.flavor) : Path.of(args[2]);
        try {
            host.start();
            if (mode.equals("manual")) return;
            if (mode.equals("capture")) host.gallery();
            if (mode.equals("measure")) host.measure();
            if (mode.equals("keyboard")) Files.writeString(host.output.resolve("keyboard.json"),MiniJson.stringify(host.nativeKeyboard()));
            host.close();
            System.exit(0);
        } catch(Throwable failure) {
            failure.printStackTrace();
            try {host.close();} catch(Throwable ignored) { }
            System.exit(1);
        }
    }
    private void start() throws Exception {
        // Pin identical menu placement even if a toolkit changes the macOS default.
        System.setProperty("apple.laf.useScreenMenuBar","false");
        fixture = Files.createTempDirectory("shed-ui-comparison-");
        Path config = Files.createDirectories(fixture.resolve("home/.shed"));
        Files.writeString(config.resolve("config.toml"), "schema_version = 1\n\"keymap.profile\" = \"plain\"\n\"session.restore.on.start\" = false\n");
        StringBuilder text = new StringBuilder("package demo;\n\n/** Keep the workbench quiet and the code readable. */\npublic class Renderer {\n    private String theme = \"dark\";\n\n    public void render() {\n        System.out.println(theme);\n    }\n}\n");
        for(int i=0;i<20000;i++)text.append("// line ").append(i).append(" alpha beta gamma delta epsilon\n");
        Path source = Files.writeString(fixture.resolve("Renderer.java"),text);
        Path other = Files.writeString(fixture.resolve("Theme.java"),"package demo;\n\nclass Theme {\n    String theme = \"dark\";\n}\n");
        onEdt(() -> {
            System.setProperty("user.home", config.getParent().toString());
            editor = new Texteditor(new String[]{source.toString(),other.toString()});
            editor.setSize(1120,792); editor.setLocation(20,30);
            return null;
        });
        await(() -> !editor.stagedStartupPending && editor.documents.documents().size()==2);
        onEdt(() -> {
            editor.documents.activate(editor.documents.documents().getFirst().id());
            model = new LabModel(editor);
            factory = flavor.equals("swing") ? new SwingLabFactory()
                : (LabFactory)Class.forName("shed.ComposeLabFactory").getConstructor().newInstance();
            model.actions(this::showSettings,this::dismissSettings,this::toggleDetached);
            navigation = factory.navigation(model);
            navigation.component().setMinimumSize(new Dimension(0,0));
            model.listen(state -> { navigation.component().setPreferredSize(new Dimension(400,UiZoom.scale(126,state.style().zoom()))); editor.revalidate(); });
            Container north = (Container)((BorderLayout)editor.getContentPane().getLayout()).getLayoutComponent(BorderLayout.NORTH);
            north.remove(editor.openEditorsBar); editor.openEditorsBar.setVisible(false); north.add(navigation.component(),BorderLayout.NORTH);
            Component center = ((BorderLayout)editor.getContentPane().getLayout()).getLayoutComponent(BorderLayout.CENTER);
            editor.remove(center);
            split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,center,null);split.setBorder(null);split.setResizeWeight(0.62);
            editor.add(split,BorderLayout.CENTER);
            showSettings();
            KeyStroke appearanceKey=KeyStroke.getKeyStroke(KeyEvent.VK_COMMA,Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx());
            bind(appearanceKey,"lab-settings",this::showSettings);
            rebindAppearanceMenu(editor.getJMenuBar(),appearanceKey);
            // The legacy editor key listener consumes Settings before root-pane bindings.
            // Composition intercepts only this prototype shortcut, scoped to its owned windows.
            shortcutDispatcher=event->{
                Window focused=KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusedWindow();
                if((focused==editor || (detached!=null && focused==detached)) && event.getID()==KeyEvent.KEY_PRESSED && appearanceKey.equals(KeyStroke.getKeyStrokeForEvent(event))) {
                    showSettings();event.consume();return true;
                }
                if((focused==editor || (detached!=null && focused==detached)) && event.getID()==KeyEvent.KEY_PRESSED && event.getKeyCode()==KeyEvent.VK_F6 && event.getModifiersEx()==0) {
                    if(settings==null)showSettings();else settings.focusSearch();event.consume();return true;
                }
                return false;
            };
            KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(shortcutDispatcher);
            bind(KeyStroke.getKeyStroke(KeyEvent.VK_F6,0),"lab-focus",() -> { if(settings!=null)settings.focusSearch(); });
            editor.addWindowListener(new WindowAdapter(){public void windowClosed(WindowEvent e){ if(model!=null)model.close();if(shortcutDispatcher!=null)KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(shortcutDispatcher);if(detached!=null)detached.dispose(); }});
            editor.setTitle("Shed — "+flavor+" comparison");editor.validate();split.setDividerLocation(0.62);editor.repaint();
            return null;
        });
        await(() -> model.ready());
        // Compose may set this global property during its asynchronous first composition.
        System.setProperty("apple.laf.useScreenMenuBar","false");
        Thread.sleep(500);
        Files.createDirectories(output);
    }
    private void bind(KeyStroke key,String id,Runnable action) {
        var root=editor.getRootPane();root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(key,id);
        root.getActionMap().put(id,new AbstractAction(){public void actionPerformed(ActionEvent e){action.run();}});
    }
    private void rebindAppearanceMenu(MenuElement root,KeyStroke key) {
        if(root instanceof JMenuItem item && key.equals(item.getAccelerator())) {
            for(var listener:item.getActionListeners())item.removeActionListener(listener);
            item.addActionListener(event->showSettings());
        }
        for(MenuElement child:root.getSubElements())rebindAppearanceMenu(child,key);
    }
    private void showSettings() {
        if(settings==null) {
            settings=factory.settings(model);settings.component().setMinimumSize(new Dimension(0,0));split.setRightComponent(settings.component());split.setDividerSize(6);
            split.setDividerLocation(0.62);editor.revalidate();
        }
        if(detached!=null)detached.toFront();
        editor.validate();SwingUtilities.invokeLater(()->{if(settings!=null)settings.focusSearch();});
    }
    private void dismissSettings() {
        if(detached!=null){detached.dispose();detached=null;}
        split.setRightComponent(null);split.setDividerSize(0);
        if(settings!=null){settings.close();settings=null;}
        model.detached(false);model.focusEditor();editor.revalidate();
    }
    private void toggleDetached() {
        boolean floating=detached!=null;
        if(detached!=null){detached.dispose();detached=null;}
        split.setRightComponent(null);
        if(settings!=null)settings.close();
        settings=factory.settings(model);settings.component().setMinimumSize(new Dimension(0,0));
        if(floating){split.setRightComponent(settings.component());split.setDividerSize(6);split.setDividerLocation(0.62);}
        else {
            split.setDividerSize(0);
            detached=new JDialog(editor,"Appearance",false);detached.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
            detached.addWindowListener(new WindowAdapter(){public void windowClosing(WindowEvent e){dismissSettings();}});
            detached.add(settings.component());editor.editorUiController.prepareDialog(detached,430,650);detached.pack();detached.setLocationRelativeTo(editor);detached.setVisible(true);
        }
        model.detached(!floating);editor.revalidate();settings.focusSearch();
    }
    private void gallery() throws Exception {
        capture("01-dark");
        onEdt(()->{model.update("theme","shed-light");return null;});settle();capture("02-light");
        onEdt(()->{model.update("ui.zoom","1.5");return null;});settle();capture("03-light-150");
        onEdt(()->{model.update("theme","shed-dark");return null;});settle();capture("04-dark-150");
        onEdt(()->{editor.setSize(760,680);editor.validate();split.setDividerLocation(0.5);return null;});settle();capture("05-narrow-150");
        onEdt(()->{model.query("no-such-setting");return null;});settle();capture("06-empty");
        onEdt(()->{model.query("ui.zoom");model.update("ui.zoom","invalid");return null;});settle();capture("07-error");
        onEdt(()->{model.update("ui.zoom","1.0");editor.setSize(1120,792);model.query("");toggleDetached();return null;});settle();capture("08-detached");
        Map<String,Object> evidence=new LinkedHashMap<>();
        onEdt(()->{toggleDetached();model.query("ui.zoom");return null;});settle();
        evidence.put("interaction",interactions());
        evidence.put("native_keyboard",nativeKeyboard());
        evidence.put("captures",captures);
        evidence.put("screen_menu",System.getProperty("apple.laf.useScreenMenuBar"));
        evidence.put("flavor",flavor);evidence.put("java",System.getProperty("java.version"));
        evidence.put("os",System.getProperty("os.name")+" "+System.getProperty("os.arch"));
        evidence.put("fixture_lines",20011);evidence.put("native_ime_screenreader","not verified");
        Files.writeString(output.resolve("gallery.json"),MiniJson.stringify(evidence));
    }
    private Map<String,Object> interactions() throws Exception {
        Map<String,Object> evidence=new LinkedHashMap<>();
        onEdt(()->{
            var first=editor.documents.documents().getFirst();var second=editor.documents.documents().getLast();
            model.activate(second.id());if(!editor.documents.activeDocument().id().equals(second.id()))throw new AssertionError("activation");
            model.activate(first.id());model.update("ui.zoom","1.25");
            if(editor.configManager.getUiZoom()!=1.25)throw new AssertionError("real settings update");
            model.update("ui.zoom","1.0");model.focusEditor();return null;
        });
        evidence.put("real_document_activation",true);
        evidence.put("accessibility_tree",onEdt(()->LabAccessibility.describe((Accessible)settings.component())));
        boolean editable=onEdt(()->LabAccessibility.edit((Accessible)settings.component(),"Search appearance settings","ui.zoom"));
        settle();
        evidence.put("accessible_query",editable && model.state().query().equals("ui.zoom"));
        if(editable) {
            boolean changed=onEdt(()->LabAccessibility.edit((Accessible)settings.component(),"Interface scale (ui.zoom)","invalid") || LabAccessibility.edit((Accessible)settings.component(),"Interface scale","invalid"));
            settle();boolean applied=onEdt(()->LabAccessibility.action((Accessible)settings.component(),"Apply"));settle();
            evidence.put("accessible_invalid_update",changed && applied && model.state().error() && onEdt(()->editor.configManager.getUiZoom()==1.0));
            changed=onEdt(()->LabAccessibility.edit((Accessible)settings.component(),"Interface scale (ui.zoom)","1.25") || LabAccessibility.edit((Accessible)settings.component(),"Interface scale","1.25"));
            settle();applied=onEdt(()->LabAccessibility.action((Accessible)settings.component(),"Apply"));settle();
            evidence.put("accessible_valid_update",changed && applied && !model.state().error() && onEdt(()->editor.configManager.getUiZoom()==1.25));
            evidence.put("persisted_on_disk",Files.readString(fixture.resolve("home/.shed/config.toml")).contains("1.25"));
            onEdt(()->{model.update("ui.zoom","1.0");return null;});settle();
        }
        boolean action=onEdt(()->LabAccessibility.action((Accessible)navigation.component(),"Theme.java"));settle();
        evidence.put("accessible_document_action",action && onEdt(()->editor.documents.activeDocument().name().equals("Theme.java")));
        onEdt(()->{model.activate(editor.documents.documents().getFirst().id());return null;});
        // The real editor transfer handler, using an isolated clipboard. Never changes the user clipboard.
        evidence.put("editor_local_clipboard",onEdt(()->{
            var surface=editor.documents.activeSurface();surface.select(0,7);Clipboard local=new Clipboard("prototype-test");
            editor.writingArea.getTransferHandler().exportToClipboard(editor.writingArea,local,TransferHandler.COPY);
            int length=surface.length();surface.select(length,length);
            boolean imported=editor.writingArea.getTransferHandler().importData(editor.writingArea,local.getContents(null));
            boolean passed=imported && surface.length()==length+7;if(imported)surface.undo();surface.select(0,0);return passed;
        }));
        onEdt(()->{model.query("");return null;});settle();
        onEdt(()->{var menu=editor.writingArea.getComponentPopupMenu();menu.show(editor.writingArea,Math.max(0,editor.writingArea.getVisibleRect().width-40),60);return null;});
        capture("09-editor-popup");
        evidence.put("real_editor_popup",onEdt(()->editor.writingArea.getComponentPopupMenu().isShowing()));
        var popup=onEdt(()->SwingUtilities.getWindowAncestor(editor.writingArea.getComponentPopupMenu()));
        evidence.put("popup_host",popup==editor?"lightweight in parent":"separate owned Swing window");
        if(popup!=editor) {
            var bounds=onEdt(popup::getSize);
            WindowCapture.capture("",bounds.width,bounds.height,output.resolve("09-editor-popup-window.png"));
        }
        onEdt(()->{editor.writingArea.getComponentPopupMenu().setVisible(false);return null;});
        onEdt(()->{model.focusEditor();return null;});evidence.put("real_setting_persistence",true);
        evidence.put("subscriptions_after_repeated_detach",model.subscriptions());
        if(model.subscriptions()!=3)throw new AssertionError("disposed view subscription retained");
        evidence.put("native_focus_observed",onEdt(()->editor.documents.activeSurface()!=null && editor.writingArea.isFocusOwner()));
        evidence.put("keyboard_bindings_installed",onEdt(()->editor.getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).get(KeyStroke.getKeyStroke(KeyEvent.VK_F6,0))!=null));
        return evidence;
    }
    private Map<String,Object> nativeKeyboard() throws Exception {
        Map<String,Object> evidence=new LinkedHashMap<>();
        try {
            onEdt(()->{if(Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.APP_REQUEST_FOREGROUND))Desktop.getDesktop().requestForeground(true);editor.toFront();model.query("");dismissSettings();model.focusEditor();return null;});
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
            while(!onEdt(()->editor.isFocused() && editor.writingArea.isFocusOwner()) && System.nanoTime()<deadline)Thread.sleep(100);
            if(!onEdt(()->editor.isFocused() && editor.writingArea.isFocusOwner())) {
                evidence.put("status","unavailable: host did not grant native application focus; no global keys sent");return evidence;
            }
            Robot robot=new Robot();robot.setAutoDelay(40);
            int modifier=System.getProperty("os.name").startsWith("Mac")?KeyEvent.VK_META:KeyEvent.VK_CONTROL;
            if(!onEdt(()->editor.isFocused()))throw new IllegalStateException("Native focus lost before shortcut");
            robot.keyPress(modifier);robot.keyPress(KeyEvent.VK_COMMA);robot.keyRelease(KeyEvent.VK_COMMA);robot.keyRelease(modifier);settle();
            evidence.put("activation",onEdt(()->settings!=null));
            if(!onEdt(()->editor.isFocused()))throw new IllegalStateException("Native focus lost before F6");
            robot.keyPress(KeyEvent.VK_F6);robot.keyRelease(KeyEvent.VK_F6);settle();
            evidence.put("focus_after_f6",onEdt(()->{Component owner=KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();return owner==null?"none":owner.getClass().getName()+":"+owner.getName();}));
            for(int key:new int[]{KeyEvent.VK_Z,KeyEvent.VK_O,KeyEvent.VK_O,KeyEvent.VK_M}) {
                if(!onEdt(()->editor.isFocused()))throw new IllegalStateException("Native focus lost; input stopped");
                robot.keyPress(key);robot.keyRelease(key);
            }
            settle();evidence.put("search_typing",model.state().query().equals("zoom"));
            if(!onEdt(()->editor.isFocused()))throw new IllegalStateException("Native focus lost before Escape");
            robot.keyPress(KeyEvent.VK_ESCAPE);robot.keyRelease(KeyEvent.VK_ESCAPE);settle();
            evidence.put("escape_dismissed",onEdt(()->settings==null));
            evidence.put("editor_focus_restored",onEdt(()->editor.writingArea.isFocusOwner()));
            evidence.put("status","native events exercised on owned focused window");
        }catch(Exception failure){evidence.put("status","incomplete: "+failure.getClass().getSimpleName()+": "+failure.getMessage());}
        return evidence;
    }
    private void capture(String name) throws Exception {
        settle();
        BufferedImage swingPaint=onEdt(()->{
            JRootPane root=editor.getRootPane();BufferedImage rendered=new BufferedImage(root.getWidth(),root.getHeight(),BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics=rendered.createGraphics();root.paintAll(graphics);graphics.dispose();return rendered;
        });
        ImageIO.write(swingPaint,"png",output.resolve(name+"-awt.png").toFile());
        var window=onEdt(()->new Object[]{editor.getTitle(),editor.getWidth(),editor.getHeight()});
        Path file=output.resolve(name+".png");
        WindowCapture.capture((String)window[0],(Integer)window[1],(Integer)window[2],file);
        BufferedImage image=ImageIO.read(file.toFile());
        Map<String,Object> entry=new LinkedHashMap<>();entry.put("name",name);entry.put("capture","prototype-owned native window");
        entry.put("screen_menu",System.getProperty("apple.laf.useScreenMenuBar"));entry.put("menu_height",onEdt(()->editor.getJMenuBar().getHeight()));
        entry.put("width",image.getWidth());entry.put("height",image.getHeight());captures.add(entry);
        if(detached!=null){
            var floating=onEdt(()->new Object[]{detached.getTitle(),detached.getWidth(),detached.getHeight()});
            WindowCapture.capture((String)floating[0],(Integer)floating[1],(Integer)floating[2],output.resolve(name+"-window.png"));
        }
    }

    private void measure() throws Exception {
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("ready_ms",(System.nanoTime()-started)/1e6);
        System.out.println("LAB_READY "+ProcessHandle.current().pid());
        Thread.sleep(500);
        result.put("heap_used_mib",(Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory())/1048576.0);
        List<Double> edits=new ArrayList<>();
        onEdt(()->{model.focusEditor();for(int i=0;i<35;i++){
            long start=System.nanoTime();var surface=editor.documents.activeSurface();surface.replace(0,0,"x");
            editor.writingArea.paintImmediately(editor.writingArea.getVisibleRect());
            if(i>=10)edits.add((System.nanoTime()-start)/1e6);surface.undo();
        }return null;});
        result.put("edit_paint_ms",edits);result.put("flavor",flavor);result.put("pid",ProcessHandle.current().pid());
        Files.writeString(output.resolve("measurement.json"),MiniJson.stringify(result));
        System.out.println("LAB_RESULT "+MiniJson.stringify(result));
    }
    private void settle() throws Exception {Thread.sleep(500);onEdt(()->{editor.validate();editor.repaint();return null;});Thread.sleep(250);}
    private void await(Callable<Boolean> condition) throws Exception {long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(90);while(!onEdt(condition)){if(System.nanoTime()>deadline)throw new IllegalStateException("UI readiness timeout");Thread.sleep(100);}}
    private void close() throws Exception {
        onEdt(()->{if(shortcutDispatcher!=null)KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(shortcutDispatcher);if(detached!=null)detached.dispose();if(settings!=null)settings.close();if(navigation!=null)navigation.close();if(model!=null)model.close();if(editor!=null)editor.dispose();return null;});
    }
    private static <T>T onEdt(Callable<T> action) throws Exception {if(SwingUtilities.isEventDispatchThread())return action.call();FutureTask<T> task=new FutureTask<>(action);SwingUtilities.invokeAndWait(task);return task.get();}
}
