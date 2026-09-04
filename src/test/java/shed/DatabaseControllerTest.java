package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.nio.file.Path;
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

    @Test
    void buildsDirectSqliteQueryAgainstAnExplicitDatabaseFile() {
        assertEquals(List.of("sqlite3", "-batch", "-bail", "/project/app.db", "select name from sqlite_master"),
            DatabaseController.sqliteQueryCommand(Path.of("/project/app.db"), "select name from sqlite_master"));
    }
}
