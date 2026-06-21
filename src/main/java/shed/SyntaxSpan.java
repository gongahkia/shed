package shed;

import java.awt.Color;

final class SyntaxSpan {
    final int start;
    final int end;
    final Color color;

    SyntaxSpan(int start, int end, Color color) {
        this.start = start;
        this.end = end;
        this.color = color;
    }
}
