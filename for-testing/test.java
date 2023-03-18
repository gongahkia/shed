// *translate these changes over to the stable version of the Texteditor.java!

import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JTextArea;
import java.awt.event.KeyListener;
import java.awt.event.KeyEvent;
import java.awt.Color;

// --- opening a file
import javax.swing.JFileChooser;
import java.io.FileReader;
import java.io.BufferedReader;
import java.io.File;

// --- writing changes to a file
import java.io.FileWriter;
import java.io.BufferedWriter;

public class test extends JFrame implements KeyListener{

    public int counter = 0;
    JLabel label1; // --- declare this variable first and then reference it later in my other overridden method!!!
    JTextArea text1;

// --- opening a file
    String currLine;
    BufferedReader bufReader;
    File targetFile;

// --- same constructor statement
    test() {
        this.setTitle("test");
        this.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        this.setLayout(null);
        this.setSize(600,630);

        text1 = new JTextArea();
        text1.setBounds(0,0,600,500);
        text1.addKeyListener(this);

// --- TO OPEN AND DISPLAY AN EDITABLE TEXT FILE IN THE TEXT AREA
        JFileChooser fileSelector = new JFileChooser();
        fileSelector.setCurrentDirectory(new File(System.getProperty("user.home")));
        int result = fileSelector.showOpenDialog(label1);

        if (result == JFileChooser.APPROVE_OPTION) {
            System.out.println("done");
            targetFile = fileSelector.getSelectedFile();

            try {

                bufReader = new BufferedReader(new FileReader(targetFile));
                text1.read(bufReader, null);
                bufReader.close();
                text1.requestFocus();
                // --- alternative implementation
                /*** while ((currLine = bufReader.readLine()) != null) {
                    System.out.println(currLine); ***/
            }

            catch (Exception e2) {
                System.out.println(e2);
            }
            
        }

        label1 = new JLabel();
        label1.setBackground(Color.lightGray);
        label1.setBounds(0,500,600,30);
        label1.setText("counter: " + counter);

        this.add(text1);
        this.add(label1);

        this.setVisible(true);


    };
    
// --- 

    @Override
    public void keyPressed(KeyEvent e) {
        if (e.getKeyChar() == 'y') {
        counter += 1;
        label1.setText("counter: " + counter);
        } else if (e.getKeyChar() == '?') {

// --- TO WRITE CHANGES TO THE AFOREMENTIONED TEXT FILE
            try {

                FileWriter writeToFile = new FileWriter(targetFile);
                BufferedWriter bufWriter = new BufferedWriter(writeToFile);
                text1.write(bufWriter);
                bufWriter.close();
                text1.setText("");
                text1.requestFocus();

            }

            catch (Exception e2) {}
        }

    }

    public void keyTyped(KeyEvent e) {}

    public void keyReleased(KeyEvent e) {}

    public static void main(String[] args) {
        new test(); 
    }

}
