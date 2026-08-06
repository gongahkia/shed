package shed;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

final class CoverageService {
    enum Format { JACOCO, COBERTURA, LCOV, GO }
    record Line(int hits, int coveredBranches, int totalBranches) {
        Line merge(Line other) { return new Line(hits + other.hits, coveredBranches + other.coveredBranches, totalBranches + other.totalBranches); }
        boolean covered() { return hits > 0; }
    }
    record Report(Map<Path, Map<Integer, Line>> files) {
        Report { files = files == null ? Map.of() : copy(files); }
        static Report empty() { return new Report(Map.of()); }
        Report merge(Report other) {
            Map<Path, Map<Integer, Line>> merged = new LinkedHashMap<>(files);
            if (other != null) for (Map.Entry<Path, Map<Integer, Line>> file : other.files.entrySet()) {
                Map<Integer, Line> lines = new LinkedHashMap<>(merged.getOrDefault(file.getKey(), Map.of()));
                for (Map.Entry<Integer, Line> line : file.getValue().entrySet()) lines.merge(line.getKey(), line.getValue(), Line::merge);
                merged.put(file.getKey(), lines);
            }
            return new Report(merged);
        }
        Summary summary() {
            int lines = 0, covered = 0, branches = 0, coveredBranches = 0;
            for (Map<Integer, Line> value : files.values()) for (Line line : value.values()) {
                lines++; if (line.covered()) covered++;
                branches += line.totalBranches(); coveredBranches += line.coveredBranches();
            }
            return new Summary(files.size(), lines, covered, branches, coveredBranches);
        }
        Map<Integer, Integer> hits(Path path) {
            Map<Integer, Integer> result = new LinkedHashMap<>();
            if (path == null) return result;
            for (Map.Entry<Integer, Line> entry : files.getOrDefault(path.toAbsolutePath().normalize(), Map.of()).entrySet()) result.put(entry.getKey() - 1, entry.getValue().hits());
            return result;
        }
        private static Map<Path, Map<Integer, Line>> copy(Map<Path, Map<Integer, Line>> source) {
            Map<Path, Map<Integer, Line>> result = new LinkedHashMap<>();
            for (Map.Entry<Path, Map<Integer, Line>> entry : source.entrySet()) result.put(entry.getKey().toAbsolutePath().normalize(), Map.copyOf(entry.getValue()));
            return Map.copyOf(result);
        }
    }
    record Summary(int files, int lines, int coveredLines, int branches, int coveredBranches) {
        String display() {
            double linePercent = lines == 0 ? 0 : 100.0 * coveredLines / lines;
            String value = String.format(Locale.ROOT, "%d/%d lines (%.1f%%)", coveredLines, lines, linePercent);
            return branches == 0 ? value : value + String.format(Locale.ROOT, ", %d/%d branches (%.1f%%)", coveredBranches, branches, 100.0 * coveredBranches / branches);
        }
    }
    record ImportResult(Format format, Report report) { }

    private static final Pattern LCOV_DA = Pattern.compile("DA:(\\d+),(\\d+)");
    private static final Pattern LCOV_BRDA = Pattern.compile("BRDA:(\\d+),[^,]*,[^,]*,(-|\\d+)");
    private static final Pattern GO_BLOCK = Pattern.compile("(.+?):(\\d+)\\.\\d+,(\\d+)\\.\\d+\\s+\\d+\\s+(\\d+)");
    private static final Pattern COBERTURA_BRANCH = Pattern.compile(".*?\\((\\d+)/(\\d+)\\).*?");

    ImportResult importReport(Path root, Path report) throws IOException {
        if (root == null || report == null) throw new IOException("workspace root and coverage report are required");
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path normalizedReport = report.isAbsolute() ? report.normalize() : normalizedRoot.resolve(report).normalize();
        if (!Files.isRegularFile(normalizedReport)) throw new IOException("coverage report not found: " + normalizedReport);
        String text = Files.readString(normalizedReport, StandardCharsets.UTF_8);
        Format format = detect(text);
        Report parsed = switch (format) {
            case JACOCO -> parseJacoco(normalizedRoot, normalizedReport);
            case COBERTURA -> parseCobertura(normalizedRoot, normalizedReport);
            case LCOV -> parseLcov(normalizedRoot, text);
            case GO -> parseGo(normalizedRoot, text);
        };
        if (parsed.files().isEmpty()) throw new IOException("coverage report contains no workspace source files");
        return new ImportResult(format, parsed);
    }

    private Format detect(String text) throws IOException {
        String value = text == null ? "" : text.stripLeading();
        if (value.startsWith("TN:") || value.startsWith("SF:")) return Format.LCOV;
        if (value.startsWith("mode:")) return Format.GO;
        if (value.contains("<report") && value.contains("<sourcefile")) return Format.JACOCO;
        if (value.contains("<coverage") && value.contains("<class")) return Format.COBERTURA;
        throw new IOException("unsupported coverage report; expected JaCoCo XML, Cobertura XML, LCOV, or Go coverprofile");
    }

    private Report parseJacoco(Path root, Path report) throws IOException {
        Map<Path, Map<Integer, Line>> files = new LinkedHashMap<>();
        try {
            org.w3c.dom.Document document = secureFactory().newDocumentBuilder().parse(report.toFile());
            NodeList packages = document.getElementsByTagName("package");
            for (int p = 0; p < packages.getLength(); p++) {
                if (!(packages.item(p) instanceof Element pkg)) continue;
                String packageName = pkg.getAttribute("name");
                NodeList sourceFiles = pkg.getElementsByTagName("sourcefile");
                for (int s = 0; s < sourceFiles.getLength(); s++) {
                    if (!(sourceFiles.item(s) instanceof Element source)) continue;
                    Path path = resolve(root, packageName.isBlank() ? source.getAttribute("name") : packageName + "/" + source.getAttribute("name"));
                    if (path == null) continue;
                    NodeList lines = source.getElementsByTagName("line");
                    for (int i = 0; i < lines.getLength(); i++) if (lines.item(i) instanceof Element line) {
                        add(files, path, integer(line, "nr"), integer(line, "ci"), integer(line, "cb"), integer(line, "cb") + integer(line, "mb"));
                    }
                }
            }
        } catch (Exception error) { throw new IOException("invalid JaCoCo XML: " + error.getMessage(), error); }
        return new Report(files);
    }

    private Report parseCobertura(Path root, Path report) throws IOException {
        Map<Path, Map<Integer, Line>> files = new LinkedHashMap<>();
        try {
            org.w3c.dom.Document document = secureFactory().newDocumentBuilder().parse(report.toFile());
            NodeList classes = document.getElementsByTagName("class");
            for (int c = 0; c < classes.getLength(); c++) {
                if (!(classes.item(c) instanceof Element clazz)) continue;
                Path path = resolve(root, clazz.getAttribute("filename"));
                if (path == null) continue;
                NodeList lines = clazz.getElementsByTagName("line");
                for (int i = 0; i < lines.getLength(); i++) if (lines.item(i) instanceof Element line) {
                    int covered = 0, total = 0;
                    Matcher matcher = COBERTURA_BRANCH.matcher(line.getAttribute("condition-coverage"));
                    if (matcher.matches()) { covered = Integer.parseInt(matcher.group(1)); total = Integer.parseInt(matcher.group(2)); }
                    add(files, path, integer(line, "number"), integer(line, "hits"), covered, total);
                }
            }
        } catch (Exception error) { throw new IOException("invalid Cobertura XML: " + error.getMessage(), error); }
        return new Report(files);
    }

    private Report parseLcov(Path root, String text) {
        Map<Path, Map<Integer, Line>> files = new LinkedHashMap<>();
        Path current = null;
        for (String row : text.lines().toList()) {
            if (row.startsWith("SF:")) { current = resolve(root, row.substring(3)); continue; }
            if (current == null) continue;
            Matcher da = LCOV_DA.matcher(row);
            if (da.matches()) { add(files, current, Integer.parseInt(da.group(1)), Integer.parseInt(da.group(2)), 0, 0); continue; }
            Matcher brda = LCOV_BRDA.matcher(row);
            if (brda.matches()) {
                int hits = "-".equals(brda.group(2)) ? 0 : Integer.parseInt(brda.group(2));
                add(files, current, Integer.parseInt(brda.group(1)), 0, hits > 0 ? 1 : 0, 1);
            }
        }
        return new Report(files);
    }

    private Report parseGo(Path root, String text) {
        Map<Path, Map<Integer, Line>> files = new LinkedHashMap<>();
        for (String row : text.lines().toList()) {
            Matcher matcher = GO_BLOCK.matcher(row.trim());
            if (!matcher.matches()) continue;
            Path path = resolve(root, matcher.group(1));
            if (path == null) continue;
            int start = Integer.parseInt(matcher.group(2));
            int end = Integer.parseInt(matcher.group(3));
            int hits = Integer.parseInt(matcher.group(4));
            for (int line = start; line <= end; line++) add(files, path, line, hits, 0, 0);
        }
        return new Report(files);
    }

    private static void add(Map<Path, Map<Integer, Line>> files, Path path, int line, int hits, int coveredBranches, int totalBranches) {
        if (path == null || line < 1) return;
        files.computeIfAbsent(path, ignored -> new LinkedHashMap<>()).merge(line, new Line(Math.max(0, hits), Math.max(0, coveredBranches), Math.max(0, totalBranches)), Line::merge);
    }

    private static int integer(Element element, String attribute) {
        try { return Integer.parseInt(element.getAttribute(attribute)); } catch (RuntimeException ignored) { return 0; }
    }

    private static Path resolve(Path root, String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            Path candidate = Path.of(raw);
            if (!candidate.isAbsolute()) candidate = root.resolve(candidate);
            candidate = candidate.normalize().toAbsolutePath();
            return candidate.startsWith(root) && Files.isRegularFile(candidate) ? candidate : null;
        } catch (RuntimeException ignored) { return null; }
    }

    private static DocumentBuilderFactory secureFactory() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        return factory;
    }
}
