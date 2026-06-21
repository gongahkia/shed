package shed;

final class MotionRange {
    final int start;
    final int end;
    final boolean lineWise;

    MotionRange(int start, int end, boolean lineWise) {
        this.start = Math.max(0, start);
        this.end = Math.max(this.start, end);
        this.lineWise = lineWise;
    }
}
