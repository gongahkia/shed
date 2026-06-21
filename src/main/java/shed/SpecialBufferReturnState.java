package shed;

final class SpecialBufferReturnState {
    final FileBuffer scratchBuffer;
    final FileBuffer returnBuffer;
    final int returnCaretPosition;

    SpecialBufferReturnState(FileBuffer scratchBuffer, FileBuffer returnBuffer, int returnCaretPosition) {
        this.scratchBuffer = scratchBuffer;
        this.returnBuffer = returnBuffer;
        this.returnCaretPosition = returnCaretPosition;
    }
}
