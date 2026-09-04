package shed;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/**
 * Extracts declaration symbols from the JDK parser without resolving a project,
 * loading annotation processors, or compiling user source. Parser diagnostics do
 * not discard recovered declarations, which keeps the outline useful while a
 * document is being edited.
 */
final class JavaStructuralService {
    record Result(List<SymbolService.Symbol> symbols, boolean parserAvailable) {
        Result {
            symbols = List.copyOf(symbols == null ? List.of() : symbols);
        }
    }

    Result collectSymbols(String source) {
        if (source == null || source.isEmpty()) return new Result(List.of(), ToolProvider.getSystemJavaCompiler() != null);
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) return new Result(List.of(), false);

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        JavaFileObject file = new SourceFile(source);
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(diagnostics, null, null)) {
            JavaCompiler.CompilationTask rawTask = compiler.getTask(null, fileManager, diagnostics, List.of("-proc:none"), null, List.of(file));
            if (!(rawTask instanceof JavacTask task)) return new Result(List.of(), true);
            Iterable<? extends CompilationUnitTree> units = task.parse();
            Trees trees = Trees.instance(task);
            List<SymbolService.Symbol> symbols = new ArrayList<>();
            for (CompilationUnitTree unit : units) {
                new DeclarationScanner(unit, trees, symbols).scan(unit, null);
            }
            return new Result(symbols, true);
        } catch (IOException | RuntimeException error) {
            // A local JDK parser must never make an editor outline unavailable.
            return new Result(List.of(), true);
        }
    }

    private static final class DeclarationScanner extends TreePathScanner<Void, Void> {
        private final CompilationUnitTree unit;
        private final Trees trees;
        private final List<SymbolService.Symbol> symbols;
        private final Deque<String> enclosingTypes = new ArrayDeque<>();
        private int executableDepth;

        private DeclarationScanner(CompilationUnitTree unit, Trees trees, List<SymbolService.Symbol> symbols) {
            this.unit = unit;
            this.trees = trees;
            this.symbols = symbols;
        }

        @Override
        public Void visitClass(ClassTree tree, Void unused) {
            String name = tree.getSimpleName().toString();
            if (!name.isBlank()) {
                add(name, classKind(tree), tree, Math.max(1, enclosingTypes.size() + 1));
                enclosingTypes.addLast(name);
                try {
                    return super.visitClass(tree, unused);
                } finally {
                    enclosingTypes.removeLast();
                }
            }
            return super.visitClass(tree, unused);
        }

        @Override
        public Void visitMethod(MethodTree tree, Void unused) {
            String name = tree.getName().toString();
            boolean constructor = "<init>".equals(name);
            if (constructor && !enclosingTypes.isEmpty()) name = enclosingTypes.getLast();
            if (!name.isBlank() && !"<init>".equals(name)) {
                add(name, constructor ? "constructor" : "method", tree, Math.max(1, enclosingTypes.size() + 1));
            }
            executableDepth++;
            try {
                return super.visitMethod(tree, unused);
            } finally {
                executableDepth--;
            }
        }

        @Override
        public Void visitVariable(VariableTree tree, Void unused) {
            if (executableDepth == 0 && !enclosingTypes.isEmpty()) {
                String name = tree.getName().toString();
                if (!name.isBlank()) add(name, "field", tree, Math.max(1, enclosingTypes.size() + 1));
            }
            return super.visitVariable(tree, unused);
        }

        private void add(String name, String kind, Tree tree, int level) {
            long start = trees.getSourcePositions().getStartPosition(unit, tree);
            if (start < 0 || unit.getLineMap() == null) return;
            long line = unit.getLineMap().getLineNumber(start);
            if (line > 0 && line <= Integer.MAX_VALUE) {
                symbols.add(new SymbolService.Symbol(name, kind, (int) line, level));
            }
        }

        private String classKind(ClassTree tree) {
            return switch (tree.getKind()) {
                case INTERFACE -> "interface";
                case ENUM -> "enum";
                case RECORD -> "record";
                case ANNOTATION_TYPE -> "annotation";
                default -> "class";
            };
        }
    }

    private static final class SourceFile extends SimpleJavaFileObject {
        private final String source;

        private SourceFile(String source) {
            super(URI.create("string:///ShedBuffer.java"), Kind.SOURCE);
            this.source = source;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return source;
        }
    }
}
