package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.awt.Dimension;
import java.awt.Point;
import java.awt.Rectangle;
import org.junit.jupiter.api.Test;

class EditorUiControllerDropPlacementTest {
    private static final Dimension EDITOR_SIZE = new Dimension(800, 600);

    @Test
    void centralLowerHalfCreatesABottomSplitAndPreview() {
        EditorUiController.FileTreeDropPlacement placement = EditorUiController.FileTreeDropPlacement.forPoint(new Point(400, 301), EDITOR_SIZE);

        assertEquals(EditorUiController.FileTreeDropPlacement.BOTTOM, placement);
        assertEquals(new Rectangle(0, 300, 800, 300), placement.previewBounds(EDITOR_SIZE));
    }

    @Test
    void sideEdgesKeepSideBySideSplitTargets() {
        assertEquals(EditorUiController.FileTreeDropPlacement.LEFT,
            EditorUiController.FileTreeDropPlacement.forPoint(new Point(199, 500), EDITOR_SIZE));
        assertEquals(EditorUiController.FileTreeDropPlacement.RIGHT,
            EditorUiController.FileTreeDropPlacement.forPoint(new Point(600, 500), EDITOR_SIZE));
        assertEquals(new Rectangle(400, 0, 400, 600),
            EditorUiController.FileTreeDropPlacement.RIGHT.previewBounds(EDITOR_SIZE));
    }

    @Test
    void measuresTheDropAndPreviewAgainstTheVisibleViewport() {
        Rectangle viewport = new Rectangle(640, 480, 800, 600);
        EditorUiController.FileTreeDropPlacement placement = EditorUiController.FileTreeDropPlacement.forPoint(new Point(1040, 781), viewport);

        assertEquals(EditorUiController.FileTreeDropPlacement.BOTTOM, placement);
        assertEquals(new Rectangle(640, 780, 800, 300), placement.previewBounds(viewport));
    }
}
