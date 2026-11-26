package shed;

import java.nio.file.Path;
import java.util.List;

/** Vendor-neutral instructions for using the actual authorized editor, never an alternate buffer. */
final class AgentTaskInstructions {
    private AgentTaskInstructions(){}
    static List<String> cli() {
        String exe=System.getProperty("os.name","").startsWith("Windows")?"java.exe":"java";
        return List.of(Path.of(System.getProperty("java.home"),"bin",exe).toString(),"-cp",
            ShedVisualize.absoluteClassPath(System.getProperty("java.class.path"),Path.of(System.getProperty("user.dir"))),"shed.Texteditor","ctl");
    }
    static String chatPrompt(String message,String agent) {
        return context(message,agent)+"This is an ongoing conversation in Shed. Reply to the human normally, retain your native conversation context, and summarize relevant changes/checks in your reply. The human can send follow-up messages. Do not call session-finish: Shed manages the chat's visualization attachment. The visualization context is refreshed on every turn; use the current environment rather than an attachment remembered from an earlier turn.\n";
    }
    static String prompt(String task,String agent) {
        return context(task,agent)+"Finish with a concise summary of changed files, checks actually run, failures and remaining work. The launcher returns control and opens the existing session change summary when your process exits. For an externally attached CLI, call session-finish --request-id <SHED_DELEGATION> --task-file - with your summary on standard input. Never claim that a successful exit proves tests passed.\n";
    }
    private static String context(String task,String agent) {
        if(task==null||task.isBlank()||task.length()>4096)throw new IllegalArgumentException("Use a task of at most 4096 characters");
        return "You are working with the human's real Shed window as "+agent+". Complete this task:\n\n"+task+"\n\n"+
            "Shed has already obtained the user's approval for this bounded session. SHED_CONTROL and SHED_DELEGATION in your environment select the authenticated instance and permission lease. Never print their values or credential contents.\n"+
            "Invoke the Shed CLI using this exact argument-array prefix (quote individual arguments for your shell): "+MiniJson.stringify(cli())+"\n"+
            "Before examining a source file, use that prefix followed by: read <project-relative-path> --agent "+MiniJson.stringify(agent)+" --offset <UTF-16-offset> --limit 2048. This opens the actual file and highlights the chunk being read. Continue with nextOffset for further chunks. Ordinary filesystem reads cannot be visualized.\n"+
            "Edit files normally through your existing tools, in the approved project only. Shed watches additions, modifications, moves and deletions. Keep your normal permission and approval rules; this session does not authorize arbitrary tools, external paths or destructive commands.\n"+
            "After writing an open file, compute SHA-256 of its exact disk bytes, then invoke: sync <relative-path> --sha256 <hash> --timeout-ms 2500. Only a successful sync confirms Shed displays those bytes. Never use an old read as proof of freshness. On timeout or dirty-buffer conflict, stop and report the conflict; do not overwrite or force reload.\n"+
            "If any Shed command returns AUTHORIZATION_REVOKED, CANCELLED, SESSION_EXPIRED or SESSION_INVALID, stop working immediately: the human has taken control back or the lease ended. Do not retry around permission failures. Do not start another server or daemon.\n";
    }
}
