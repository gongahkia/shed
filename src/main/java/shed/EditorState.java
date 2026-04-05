package shed;

public class EditorState {
    public EditorMode mode;
    public String commandBuffer;
    public char pendingKey;
    public String pendingCount;
    public Character pendingRegister;
    public int visualStartPos;
    public boolean searchForward;

    public EditorState() {
        mode = EditorMode.NORMAL;
        commandBuffer = "";
        pendingKey = '\0';
        pendingCount = "";
        pendingRegister = null;
        visualStartPos = -1;
        searchForward = true;
    }
}
