import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class RegisterManager {
    private final Map<Character, RegisterContent> namedRegisters;
    private RegisterContent unnamedRegister;
    private RegisterContent lastYankRegister;
    private RegisterContent lastDeleteRegister;
    private String currentFilename;
    private String lastCommand;
    private String lastInserted;
    private final Clipboard systemClipboard;

    public RegisterManager() {
        this.namedRegisters = new HashMap<>();
        this.unnamedRegister = RegisterContent.characterWise("");
        this.lastYankRegister = RegisterContent.characterWise("");
        this.lastDeleteRegister = RegisterContent.characterWise("");
        this.currentFilename = "";
        this.lastCommand = "";
        this.lastInserted = "";
        this.systemClipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
    }

    public void setYank(Character register, RegisterContent content) {
        if (content == null) {
            return;
        }
        lastYankRegister = content;
        set(register, content);
    }

    public void setDelete(Character register, RegisterContent content) {
        if (content == null) {
            return;
        }
        lastDeleteRegister = content;
        set(register, content);
    }

    public RegisterContent get(Character register) {
        if (register == null || register == '"') {
            return unnamedRegister;
        }
        switch (register) {
            case '0':
                return lastYankRegister;
            case '%':
                return RegisterContent.characterWise(currentFilename);
            case ':':
                return RegisterContent.characterWise(lastCommand);
            case '.':
                return RegisterContent.characterWise(lastInserted);
            case '_':
                return null;
            case '+':
            case '*':
                return RegisterContent.characterWise(readSystemClipboard());
            default:
                return namedRegisters.get(register);
        }
    }

    public void updateFilename(String filename) {
        this.currentFilename = filename == null ? "" : filename;
    }

    public void updateLastCommand(String command) {
        this.lastCommand = command == null ? "" : command;
    }

    public void updateLastInserted(String inserted) {
        this.lastInserted = inserted == null ? "" : inserted;
    }

    public List<String> getDisplayLines() {
        List<String> lines = new ArrayList<>();
        appendLine(lines, '"', unnamedRegister);
        appendLine(lines, '0', lastYankRegister);
        appendLine(lines, '%', RegisterContent.characterWise(currentFilename));
        appendLine(lines, ':', RegisterContent.characterWise(lastCommand));
        appendLine(lines, '.', RegisterContent.characterWise(lastInserted));
        appendLine(lines, '+', RegisterContent.characterWise(readSystemClipboard()));

        List<Character> keys = new ArrayList<>(namedRegisters.keySet());
        keys.sort(Character::compareTo);
        for (Character key : keys) {
            appendLine(lines, key, namedRegisters.get(key));
        }
        return lines;
    }

    private void appendLine(List<String> lines, char key, RegisterContent content) {
        if (content == null || content.getText().isEmpty()) {
            return;
        }
        lines.add(key + " " + content.displayText());
    }

    private void set(Character register, RegisterContent content) {
        if (register != null && register == '_') {
            return;
        }
        unnamedRegister = content;
        if (register == null || register == '"') {
            return;
        }
        if (register == '+' || register == '*') {
            writeSystemClipboard(content.getText());
            return;
        }
        if (register == '%' || register == ':' || register == '.') {
            return;
        }
        namedRegisters.put(register, content);
    }

    private String readSystemClipboard() {
        try {
            Object data = systemClipboard.getData(DataFlavor.stringFlavor);
            return data == null ? "" : data.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private void writeSystemClipboard(String text) {
        try {
            systemClipboard.setContents(new StringSelection(text == null ? "" : text), null);
        } catch (IllegalStateException ignored) {
        }
    }
}
