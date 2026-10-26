package shed;

/** Recoverable model-output error; never used for stale versions or permission failures. */
final class AgentReplyFormatException extends IllegalArgumentException {
    AgentReplyFormatException(String message) { super(message); }

    String correction(AgentAction action) {
        String schema = switch (action) {
            case ASK -> "{\"answer\":\"plain text\",\"locations\":[{\"path\":\"relative/file\",\"line\":1,\"column\":1,\"note\":\"why relevant\"}]}";
            case EDIT -> "{\"replacement\":\"new text for the original selection only\"}";
            case RUN -> "{\"summary\":\"plain text\",\"changes\":[{\"kind\":\"edit\",\"path\":\"relative/file\",\"edits\":[{\"before\":\"unique exact original text\",\"after\":\"replacement\"}]}]}";
        };
        return "Repair the response format for the previous request. No change has been applied. "
            + "Use the SAME original instruction, selection, versions and unsaved context. Return only valid JSON, without commentary or Markdown. "
            + "Required shape: " + schema + ". Empty locations/changes arrays are allowed when appropriate. "
            + "For tasks, the original create/delete/rename forms are also allowed. Do not write files, run commands, or change the task. "
            + "Validation error (data): " + MiniJson.stringify(getMessage());
    }
}
