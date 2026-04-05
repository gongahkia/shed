package shed;

public class EditorState {
    public EditorMode mode;
    public String commandBuffer;
    public char pendingKey;
    public String pendingCount;
    public Character pendingRegister;
    public int visualStartPos;
    public boolean searchForward;
    public int lastVisualStart;
    public int lastVisualEnd;
    public EditorMode lastVisualMode;
    public int searchStartPos;
    public int visualBlockStartLine;
    public int visualBlockStartCol;

    public EditorState() {
        mode = EditorMode.NORMAL;
        commandBuffer = "";
        pendingKey = '\0';
        pendingCount = "";
        pendingRegister = null;
        visualStartPos = -1;
        searchForward = true;
        lastVisualStart = -1;
        lastVisualEnd = -1;
        lastVisualMode = null;
        searchStartPos = -1;
        visualBlockStartLine = -1;
        visualBlockStartCol = -1;
    }
}
