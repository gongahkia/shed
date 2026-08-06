package shed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class FormatterPolicyTest {
    @Test
    void validatesDirectFormatterNamespaceAndParsesQuotedArgv() {
        assertNull(FormatterPolicy.validateConfig("formatter.py.mode", "external"));
        assertNull(FormatterPolicy.validateConfig("formatter.py.command", "ruff"));
        assertNull(FormatterPolicy.validateConfig("formatter.py.args", "format --stdin-filename '${file}'"));
        assertNull(FormatterPolicy.validateConfig("formatter.py.format.on.save", "true"));
        assertTrue(FormatterPolicy.validateConfig("formatter.py.mode", "shell") != null);
        assertTrue(FormatterPolicy.validateConfig("formatter.py.unknown", "x") != null);
        assertEquals(List.of("format", "--stdin-filename", "${file}"), FormatterPolicy.parseArguments("format --stdin-filename '${file}'"));
    }
}
