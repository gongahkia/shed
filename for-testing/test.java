// *translate these changes over to the stable version of the Texteditor.java!

import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JTextArea;
import java.awt.event.KeyListener;
import java.awt.event.KeyEvent;
import java.awt.Color;

public class test extends JFrame implements KeyListener{

    public int counter = 0;
    JLabel label1; // --- declare this variable first and then reference it later in my other overridden method!!!

    test() {
        this.setTitle("test");
        this.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        this.setLayout(null);
        this.setSize(600,630);

        JTextArea text1 = new JTextArea();
        text1.setBounds(0,0,600,500);
        text1.addKeyListener(this);

        label1 = new JLabel();
        label1.setBackground(Color.lightGray);
        label1.setBounds(0,500,600,30);
        label1.setText("counter: " + counter);

        this.add(text1);
        this.add(label1);

        this.setVisible(true);
    };
    
    @Override
    public void keyPressed(KeyEvent e) {
        if (e.getKeyChar() == 'y') {
        counter += 1;
        label1.setText("counter: " + counter);
        }
    }

    public void keyTyped(KeyEvent e) {}

    public void keyReleased(KeyEvent e) {}

    public static void main(String[] args) {
        new test(); 
    }

}
