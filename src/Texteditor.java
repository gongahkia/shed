// figure out how to implement packages later

import javax.swing.JFrame;
import javax.swing.JTextArea;
import java.awt.Dimension;
import java.awt.event.KeyListener;
import java.awt.event.KeyEvent; // Keyevent is a data type, used with Class KeyListener
import java.awt.Toolkit;

public class Texteditor extends JFrame implements KeyListener { // taking JFrame as the parent class, Texteditor as the child class

// --- static attributes
    static int editorMode = 1; // 0: Normal mode, 1: Insert mode, 2: Command mode

// --- constructor method
    Texteditor() {
        
        // --- initializing JFrame with JTextArea
        this.setTitle("Shed");
        this.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        Dimension machinescreen = Toolkit.getDefaultToolkit().getScreenSize();
        this.setSize(machinescreen.width/2, machinescreen.height);

        JTextArea writingArea = new JTextArea();
        writingArea.addKeyListener(this);
        this.add(writingArea);

        this.setVisible(true);
// * ADD A TEXT LABEL AREA THAT UPDATES THE MODE OF THE EDITOR BASED ON THE CASE NUMBER
// * ADD A TEXT LABEL AREA THAT SHOWS CURRENT COMMAND ENTERED DURING NORMAL MODE
    }

// --- methods implemented from the KeyListener interface 

    // --- keyPressed method called whenever a key is pressed (ie. a KeyEvent triggered)
    @Override
    public void keyPressed(KeyEvent e) {
        // --- to check editors current mode
        switch(editorMode) {

            case 0: // 0: normal mode --> (navigation with cursor), (entering insert mode), (enter command mode)
                System.out.println("normal mode");
                // --- 'i', 'I', 'a', 'A', 'o', 'O' bring you to insert mode
                if (e.getKeyChar() == 'i') {
                    editorMode = 1;
                } else if (e.getKeyChar() == 'a') {
                    editorMode = 1;
                } else if (e.getKeyChar() == 'o') {
                    editorMode = 1;
                } else if (e.getKeyChar() == 'I') {
                    editorMode = 1;
                } else if (e.getKeyChar() == 'A') {
                    editorMode = 1;
                } else if (e.getKeyChar() == 'O') {
                    editorMode = 1;
// * IMPLEMENT LOGIC FOR THE ABOVE POSITION OF THE CURSOR
                // --- 'k', 'j', 'w' and 'b' move cursor up and down, one word forward and one word back
                } else if (e.getKeyChar() == 'j') {
                    
                } else if (e.getKeyChar() == 'k') {

                } else if (e.getKeyChar() == 'w') {

                } else if (e.getKeyChar() == 'b') { 
// * IMPLEMENT LOGIC FOR THIS ABOVE PORTION REGARDING CURSOR NAVIGATION (no change in mode)
                // --- 'u' undos the previous change
                } else if (e.getKeyChar() == 'u') {
                    System.out.println("Change undone");
// * IMPLEMENT LOGIC FOR THE ABOVE UNDO COMMAND 
                // --- ':' brings you to command mode
                } else if (e.getKeyChar() == ':') {
                    editorMode = 2;
                } else {};
                break;

            case 1: // 1: insert mode --> (typing)
                System.out.println("insert mode");
                if (e.getKeyCode() == KeyEvent.VK_ESCAPE) { // --- escape key brings you to normal mode
                    editorMode = 0;
                } else {
                    System.out.println(e.getKeyChar());
                }
// * DO I NEED TO IMPLEMENT LOGIC FOR DELETING CHARACTERS IN THIS, OR DOES JTEXTAREA DO IT FOR ME?`
                break;

            case 2: // 2: command mode
                System.out.println("command mode");
                if (e.getKeyChar() == 'w') {
                    System.out.println("Changes written (saved)"); 
                    editorMode = 0;
// * IMPLEMENT LOGIC TO SAVE CHANGES TO FILE
                } else if (e.getKeyChar() == 'q') {
                    System.out.println("Quitting now. Make sure you have saved your changes!");
                    editorMode = 0;
// * IMPLEMENT LOGIC TO QUIT PROGRAM 
                } else {
                    System.out.println("Command not recognised");
                    editorMode = 0;
                };
                break;

            default:
                System.out.println("edge case detected");
        }
        // ^^ Merge below logic within the relevant mode above!
        // ^^ add static method logic to the above chunk for true modal editing, detect i, a, o, u, <CR>-r keys, j, k, b, w
    }
    
    public void keyReleased (KeyEvent e) {}
    public void keyTyped (KeyEvent e) {}
}
