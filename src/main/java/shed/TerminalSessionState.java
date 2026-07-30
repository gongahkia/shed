package shed;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;

record TerminalSessionState(int paneIndex, String workingDirectory) {
    TerminalSessionState {
        if (paneIndex < 0) {
            throw new IllegalArgumentException("terminal pane index must be non-negative");
        }
        if (workingDirectory == null || workingDirectory.isBlank() || !new File(workingDirectory).isAbsolute()) {
            throw new IllegalArgumentException("terminal working directory must be absolute");
        }
    }

    Map<String, Object> toMap() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("paneIndex", paneIndex);
        values.put("workingDirectory", workingDirectory);
        return values;
    }

    static ParseResult parseAll(Object value) {
        List<Object> entries = MiniJson.asArray(value);
        if (entries == null) {
            return new ParseResult(List.of(), 0);
        }
        List<TerminalSessionState> states = new ArrayList<>();
        Set<Integer> paneIndexes = new HashSet<>();
        int ignored = 0;
        for (Object entry : entries) {
            Map<String, Object> fields = MiniJson.asObject(entry);
            Integer paneIndex = fields == null ? null : paneIndex(fields.get("paneIndex"));
            String workingDirectory = fields == null ? null : MiniJson.asString(fields.get("workingDirectory"));
            if (paneIndex == null || paneIndex < 0 || workingDirectory == null || workingDirectory.isBlank()) {
                ignored++;
                continue;
            }
            File directory = new File(workingDirectory);
            if (!directory.isAbsolute()) {
                ignored++;
                continue;
            }
            if (!paneIndexes.add(paneIndex)) {
                ignored++;
                continue;
            }
            states.add(new TerminalSessionState(paneIndex, directory.getAbsolutePath()));
        }
        return new ParseResult(List.copyOf(states), ignored);
    }

    private static Integer paneIndex(Object value) {
        if (!(value instanceof Number)) {
            return null;
        }
        Number number = (Number) value;
        long index = number.longValue();
        if (index < 0 || index > Integer.MAX_VALUE || Double.compare(number.doubleValue(), (double) index) != 0) {
            return null;
        }
        return (int) index;
    }

    record ParseResult(List<TerminalSessionState> states, int ignored) {
        ParseResult {
            states = List.copyOf(states);
            if (ignored < 0) {
                throw new IllegalArgumentException("ignored terminal state count must be non-negative");
            }
        }
    }
}
