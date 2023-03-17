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
        
    }

// --- methods implemented from the KeyListener interface 

    // --- keyPressed method called whenever a key is pressed (ie. a KeyEvent triggered)
    @Override
    public void keyPressed(KeyEvent e) {
        // --- to check editors current mode
        switch(editorMode) {

            case 0: // 0: normal mode
                System.out.println("normal mode");
                break;

            case 1: // 1: insert mode
                if (e.getKeyCode() == KeyEvent.VK_ESCAPE) { // escape key brings you to normal mode
                    editorMode = 0;
                } else {
                    System.out.println(e.getKeyChar());
                }
                System.out.println("insert mode");
                break;

            case 2: // 2: command mode
                System.out.println("command mode");
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
