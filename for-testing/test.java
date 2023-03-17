// *translate these changes over to the stable version of the Texteditor.java!
// *FIGURE OUT HOW TO SHOW A CHANGING VALUE USING JLABEL ON MY JFRAME USING THIS EXAMPLE PROJECT, WHY TEXT ISNT SHOWING?
// *REFER TO THIS

import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JTextArea;
import javax.swing.BorderFactory;
import javax.swing.border.Border;
import java.awt.event.KeyListener;
import java.awt.event.KeyEvent;
import java.awt.Dimension;
import java.awt.Color;
import java.awt.Font;
import java.awt.GridLayout;

public class test extends JFrame implements KeyListener{

    static int counter = 0;

    test() {
        this.setTitle("test");
        this.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        this.setSize(600,600);
        this.setLayout(new GridLayout(1,2,10,0));
        
        JTextArea text1 = new JTextArea();
        text1.addKeyListener(this);
        this.add(text1);

        Border border1 = BorderFactory.createLineBorder(Color.green,3);
        JLabel label1 = new JLabel();
        label1.setHorizontalTextPosition(JLabel.CENTER);
        label1.setVerticalTextPosition(JLabel.CENTER);
        label1.setForeground(Color.ORANGE);
        label1.setFont(new Font("Comic Sans", Font.PLAIN, 20));
        label1.setText(Integer.toString(counter));
        label1.setBackground(Color.black);
        label1.setOpaque(true);
        label1.setBorder(border1);

        this.add(label1);
        this.setVisible(true);
    };

    @Override 
    public void keyPressed(KeyEvent e) {
        if (e.getKeyChar() != 'y') {
            System.out.println("Shit");
        } else {
            counter += 1;
            label1.setText(Integer.toString(counter));
            System.out.println(counter);
        }
    }

    public void keyTyped(KeyEvent e) {}

    public void keyReleased(KeyEvent e) {}

    public static void main(String[] args) {
        new test(); 
    }

}
