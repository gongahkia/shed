package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

public class GitHunkNavigationTest {
    @Test
    void parsesUnifiedDiffRangesForDocumentNavigation() {
        String diff = "@@ -4,2 +4,3 @@ method\n-old\n+new\n+extra\n@@ -12 +13 @@\n-old\n+new\n@@ -20,3 +20,0 @@ removed\n-old\n";

        List<GitHunkNavigation.Hunk> hunks = GitHunkNavigation.parse(diff);

        assertEquals(3, hunks.size());
        assertEquals(4, hunks.get(0).targetLine());
        assertEquals(13, hunks.get(1).targetLine());
        assertEquals(20, hunks.get(2).targetLine());
        assertTrue(hunks.get(0).toString().contains("method"));
    }
}
