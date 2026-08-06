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
    boolean definition,
    boolean typeDefinition,
    boolean callHierarchy,
    boolean typeHierarchy,
    boolean references,
    boolean rename,
    boolean codeActions,
    boolean commandExecution,
    boolean formatting
) {
    static LspFeatureSettings defaults() {
        return new LspFeatureSettings(true, false, true, true, true, true, true, true, true, true, true, true, true, true, true);
    }

    Map<LspCapability, Boolean> capabilityEnablement() {
        Map<LspCapability, Boolean> enabled = new EnumMap<>(LspCapability.class);
        enabled.put(LspCapability.COMPLETION, completion);
        enabled.put(LspCapability.SIGNATURE_HELP, signatureHelp);
        enabled.put(LspCapability.HOVER, hover);
        enabled.put(LspCapability.DEFINITION, definition);
        enabled.put(LspCapability.TYPE_DEFINITION, typeDefinition);
        enabled.put(LspCapability.CALL_HIERARCHY, callHierarchy);
        enabled.put(LspCapability.TYPE_HIERARCHY, typeHierarchy);
        enabled.put(LspCapability.REFERENCES, references);
        enabled.put(LspCapability.RENAME, rename);
        enabled.put(LspCapability.CODE_ACTION, codeActions);
        enabled.put(LspCapability.EXECUTE_COMMAND, commandExecution);
        enabled.put(LspCapability.FORMATTING, formatting);
        enabled.put(LspCapability.SEMANTIC_TOKENS, semanticTokens);
        enabled.put(LspCapability.INLAY_HINTS, inlayHints);
        return enabled;
    }
}
