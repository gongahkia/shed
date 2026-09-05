package shed;

import java.util.EnumMap;
import java.util.Map;

record LspFeatureSettings(
    boolean completion,
    boolean snippets,
    boolean signatureHelp,
    boolean hover,
    boolean semanticTokens,
    boolean inlayHints,
    boolean codeLens,
    boolean selectionRanges,
    boolean documentLinks,
    boolean documentColors,
    boolean pullDiagnostics,
    boolean workspaceDiagnostics,
    boolean foldingRanges,
    boolean definition,
    boolean typeDefinition,
    boolean implementation,
    boolean documentHighlights,
    boolean callHierarchy,
    boolean typeHierarchy,
    boolean references,
    boolean rename,
    boolean codeActions,
    boolean commandExecution,
    boolean formatting
) {
    static LspFeatureSettings defaults() {
        return new LspFeatureSettings(true, false, true, true, true, true, true, true, true, true, true, true, true, true, true, true, true, true, true, true, true, true, true, true);
    }

    Map<LspCapability, Boolean> capabilityEnablement() {
        Map<LspCapability, Boolean> enabled = new EnumMap<>(LspCapability.class);
        enabled.put(LspCapability.COMPLETION, completion);
        enabled.put(LspCapability.SIGNATURE_HELP, signatureHelp);
        enabled.put(LspCapability.HOVER, hover);
        enabled.put(LspCapability.DEFINITION, definition);
        enabled.put(LspCapability.TYPE_DEFINITION, typeDefinition);
        enabled.put(LspCapability.IMPLEMENTATION, implementation);
        enabled.put(LspCapability.DOCUMENT_HIGHLIGHTS, documentHighlights);
        enabled.put(LspCapability.CALL_HIERARCHY, callHierarchy);
        enabled.put(LspCapability.TYPE_HIERARCHY, typeHierarchy);
        enabled.put(LspCapability.REFERENCES, references);
        enabled.put(LspCapability.RENAME, rename);
        enabled.put(LspCapability.CODE_ACTION, codeActions);
        enabled.put(LspCapability.EXECUTE_COMMAND, commandExecution);
        enabled.put(LspCapability.FORMATTING, formatting);
        enabled.put(LspCapability.SEMANTIC_TOKENS, semanticTokens);
        enabled.put(LspCapability.INLAY_HINTS, inlayHints);
        enabled.put(LspCapability.CODE_LENS, codeLens);
        enabled.put(LspCapability.SELECTION_RANGES, selectionRanges);
        enabled.put(LspCapability.DOCUMENT_LINKS, documentLinks);
        enabled.put(LspCapability.DOCUMENT_COLORS, documentColors);
        enabled.put(LspCapability.PULL_DIAGNOSTICS, pullDiagnostics);
        enabled.put(LspCapability.WORKSPACE_DIAGNOSTICS, workspaceDiagnostics);
        enabled.put(LspCapability.FOLDING_RANGES, foldingRanges);
        return enabled;
    }
}
