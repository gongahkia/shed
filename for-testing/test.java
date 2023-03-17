// *translate these changes over to the stable version of the Texteditor.java!

import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JTextArea;
import java.awt.event.KeyListener;
import java.awt.event.KeyEvent;

public class test extends JFrame implements KeyListener{

    public int counter = 0;
    JLabel label1; // --- declare this variable first and then reference it later in my other overridden method!!!

    test() {
        this.setTitle("test");
        this.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        this.setLayout(null);
        this.setSize(600,600);

        label1 = new JLabel();
        label1.setBounds(50,25,100,30); 
        
        JTextArea text1 = new JTextArea();
        text1.setBounds(20,75,250,200);
        text1.addKeyListener(this);

        this.add(text1);
        this.add(label1);

        this.setVisible(true);
    };
    
    @Override
    public void keyPressed(KeyEvent e) {
        counter += 1;
        System.out.println(counter);
        label1.setText("counter: " + counter);
    }

    public void keyTyped(KeyEvent e) {}

    public void keyReleased(KeyEvent e) {}

    public static void main(String[] args) {
        new test(); 
    }

}
