// figure out how to implement packages later

import javax.swing.JFrame;
import javax.swing.JTextArea;
import java.awt.Dimension;
import java.awt.event.KeyListener;
import java.awt.event.KeyEvent; // Keyevent is a data type, used with Class KeyListener
import java.awt.Toolkit;

public class Texteditor {
    
    // constructor method
    Texteditor() {
        Dimension machinescreen = Toolkit.getDefaultToolkit().getScreenSize();

        JFrame baseFrame = new JFrame("Shed");
        baseFrame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        baseFrame.setSize(machinescreen.width/2, machinescreen.height);

        JTextArea writingArea = new JTextArea();
        writingArea.addKeyListener(new KeyChecker());

        baseFrame.add(writingArea);

        baseFrame.setVisible(true);
    }

    // runtime 
    public static void main(String[] args) {
        Texteditor t1 = new Texteditor();
        // System.out.println(writingArea.getText());
    }

}

class KeyChecker implements KeyListener { // for some reason, this allows me to access KeyChecker class. Figure out why!

    public void keyTyped(KeyEvent theevent) {
    }

    public void keyPressed(KeyEvent theevent) {
        if (theevent.getKeyCode() == KeyEvent.VK_ESCAPE) {
            System.out.println("Normal mode");
        } else {
            System.out.println("my man");
        }
    }

    public void keyReleased(KeyEvent theevent) {
    }

}
