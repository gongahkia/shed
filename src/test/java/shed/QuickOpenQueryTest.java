package shed;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class QuickOpenQueryTest {
    @Test
    void separatesLocationsWithoutConfusingDriveLettersOrSpaces() {
        assertEquals(new QuickOpenQuery("src/My File.java", 42, 7), QuickOpenQuery.parse("src/My File.java:42:7"));
        assertEquals(new QuickOpenQuery("C:\\work\\Main.java", 12, 1), QuickOpenQuery.parse("C:\\work\\Main.java:12"));
        assertEquals(new QuickOpenQuery("dir:part/file", 12, 3), QuickOpenQuery.parse("dir:part/file:12:3"));
        assertEquals(new QuickOpenQuery("", 10, 2), QuickOpenQuery.parse(":10:2"));
    }

    @Test
    void leavesOrdinaryNamesAndInvalidLocationsLiteral() {
        for (String query : new String[] {"C:\\work\\Main.java", "notes:today", "file:0", "file:10:0",
            "file:-1", "file:9999999999999999999", "file:3:", ""}) {
            assertEquals(new QuickOpenQuery(query, 0, 0), QuickOpenQuery.parse(query));
        }
    }
}
