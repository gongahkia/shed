// * figure out how to implement packages later
// * customise text color and background color for a cool theme

// --- JFrame GUI and components
import javax.swing.JFrame;
import javax.swing.JTextArea;
import javax.swing.JLabel;
import java.awt.event.KeyListener;
import java.io.BufferedReader;
import java.awt.event.KeyEvent; // Keyevent is a data type, used with Class KeyListener
import java.awt.event.WindowEvent;
import java.awt.Toolkit;
import java.awt.Dimension;
import java.awt.BorderLayout;
import java.awt.Color;

// --- opening a file
import javax.swing.JFileChooser;
import java.io.FileReader;
import java.io.BufferedReader;
import java.io.File;

// --- writing changes to a file
import java.io.FileWriter;
import java.nio.Buffer;
import java.io.BufferedWriter;

public class Texteditor extends JFrame implements KeyListener { // taking JFrame as the parent class, Texteditor as the child class

// --- static attributes
    static int editorMode = 0; // 0: Normal mode, 1: Insert mode, 2: Command mode
    JLabel editorModeLabel;
    JTextArea writingArea;

// --- opening a file
    String currentLine;
    BufferedReader bufReader;
    File targetFile;

// --- constructor method
    Texteditor() {
        
        // --- initializing JFrame with JTextArea
        this.setTitle("Shed");
        this.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        Dimension machinescreen = Toolkit.getDefaultToolkit().getScreenSize();
        this.setSize(machinescreen.width/2, machinescreen.height);
        this.setLayout(new BorderLayout(5,5));

        writingArea = new JTextArea();
        writingArea.setPreferredSize(new Dimension(machinescreen.width/2, machinescreen.height -130));
        // writingArea.setBounds(0,0,machinescreen.width/2, machinescreen.height - 130);
        writingArea.addKeyListener(this);

        editorModeLabel = new JLabel();
        editorModeLabel.setBackground(Color.lightGray);
        editorModeLabel.setPreferredSize(new Dimension(machinescreen.width/2, 30));
        // editorModeLabel.setBounds(0, machinescreen.height - 130, machinescreen.width/2, 30);
        editorModeLabel.setText("normal mode");

// --- Open and display an editable text file in text area
        JFileChooser fileSelector = new JFileChooser();
        fileSelector.setCurrentDirectory(new File(System.getProperty("user.home")));
        int result = fileSelector.showOpenDialog(editorModeLabel);

        if (result == JFileChooser.APPROVE_OPTION) {
            targetFile = fileSelector.getSelectedFile();

            try {
                bufReader = new BufferedReader(new FileReader(targetFile));
                writingArea.read(bufReader, null);
                bufReader.close();
                writingArea.requestFocus();
            }

            catch (Exception e2) {
                System.out.println(e2);
            }

        }

        this.add(writingArea, BorderLayout.NORTH);
        this.add(editorModeLabel, BorderLayout.SOUTH);

        this.setVisible(true);
// * ADD A TEXT LABEL AREA THAT SHOWS CURRENT COMMAND ENTERED DURING NORMAL MODE
    }

// --- methods implemented from the KeyListener interface 

    // --- keyPressed method called whenever a key is pressed (ie. a KeyEvent triggered)
    @Override
    public void keyPressed(KeyEvent e) {
        // --- to check editors current mode
        switch(editorMode) {

            case 0: // 0: normal mode --> (navigation with cursor), (entering insert mode), (enter command mode)
                editorModeLabel.setText("normal mode");
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
                editorModeLabel.setText("insert mode");
                System.out.println("insert mode");
                if (e.getKeyCode() == KeyEvent.VK_ESCAPE) { // --- escape key brings you to normal mode
                    editorMode = 0;
                } else {
                    System.out.println(e.getKeyChar());  // * REMEMBER TO HASH THIS OUT EVENTUALLY ONCE THE EDITOR HAS BEEN TESTED
                }
// * DO I NEED TO IMPLEMENT LOGIC FOR DELETING CHARACTERS IN THIS, OR DOES JTEXTAREA DO IT FOR ME?`
                break;

            case 2: // 2: command mode
                // editorModeLabel.setText("command mode");
                System.out.println("command mode");
                if (e.getKeyChar() == 'w') {
                    System.out.println("Changes written (saved)"); 
                    editorModeLabel.setText("Changes written.");
                    // --- saves and writes changes to the same file
                    try {
                        FileWriter writeToFile = new FileWriter(targetFile);
                        BufferedWriter bufWriter = new BufferedWriter(writeToFile);
                        writingArea.write(bufWriter);
                        bufWriter.close();
                        // writingArea.setText(""); --- to give the impression of clearing the screen
                        writingArea.requestFocus();
                    }

                    catch (Exception e2) {}

                    editorMode = 0;

                } else if (e.getKeyChar() == 'q') {
                    System.out.println("Quitting now. Your changes have been saved!");
                    editorModeLabel.setText("Quitting.");
                    // --- quietly saves changes to the same file, and exits the program
                    try {
                        FileWriter writeToFile = new FileWriter(targetFile);
                        BufferedWriter bufWriter = new BufferedWriter(writeToFile);
                        writingArea.write(bufWriter);
                        bufWriter.close();
                        this.dispatchEvent(new WindowEvent(this, WindowEvent.WINDOW_CLOSING));
                    }

                    catch (Exception e2) {}

                } else {
                    System.out.println("Command not recognised");
                    editorModeLabel.setText("Command not recognised");
                    editorMode = 0;
                };
                break;
// * IMPLEMENT LOGIC TO REMOVE THE ':' CHARACTER THAT IS INSERTED BEFORE THE ':w' and ':q' COMMANDS

            default:
                System.out.println("edge case detected");
        }
    }
    
    public void keyReleased (KeyEvent e) {}
    public void keyTyped (KeyEvent e) {}
}
