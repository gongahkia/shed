package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class DatabaseControllerTest {
    @Test
    void buildsDirectPsqlQueryWithNoPsqlrcAndStopOnError() {
        assertEquals(List.of("psql", "--no-psqlrc", "--set", "ON_ERROR_STOP=on", "--command", "select 1"),
            DatabaseController.queryCommand("select 1"));
    }

    @Test
    void rejectsUnsafeOrEmptySqlCommandText() {
        assertThrows(IllegalArgumentException.class, () -> DatabaseController.queryCommand(""));
        assertThrows(IllegalArgumentException.class, () -> DatabaseController.queryCommand("select 1\nselect 2"));
    }
}
