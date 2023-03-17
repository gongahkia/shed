// figure out how to implement packages later

import javax.swing.JFrame;
import javax.swing.JTextArea;
import java.awt.Dimension;
import java.awt.event.KeyListener;
import java.awt.event.KeyEvent; // Keyevent is a data type, used with Class KeyListener
import java.awt.Toolkit;

public class Texteditor extends JFrame implements KeyListener { // taking JFrame as the parent class, Texteditor as the child class

// --- static attributes
    static int editorMode = 0; // 0: Normal mode, 1: Insert mode, 2: Command mode

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

    @Override
    public void keyPressed(KeyEvent e) {
        if (e.getKeyCode() == KeyEvent.VK_ESCAPE) { // escape key recognized
            System.out.println("Normal mode");
        } else if (e.getKeyCode() == KeyEvent.VK_BACK_SPACE) {
            System.out.println("deleted");
        } else {
            System.out.println(e.getKeyChar()); // other keys recognized
        } 
    }
    // ^^ add static method logic to the above chunk for true modal editing, detect i, a, o, u, <CR>-r keys, j, k, b, w
    
    public void keyReleased (KeyEvent e) {}
    public void keyTyped (KeyEvent e) {}
}
