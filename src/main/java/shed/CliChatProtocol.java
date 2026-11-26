package shed;

import java.util.*;

/** Native CLI conversation IDs and bounded JSONL decoding; no transcript is replayed as fake context. */
final class CliChatProtocol {
    enum Provider {
        CODEX("Codex", "codex"), CLAUDE("Claude Code", "claude"), OPENCODE("OpenCode", "opencode");
        final String title,executable;
        Provider(String title,String executable){this.title=title;this.executable=executable;}
        List<String> command(String executable,String session) throws java.io.IOException {
            if(session!=null&&!session.matches("[A-Za-z0-9_-]{1,128}"))throw new IllegalArgumentException("Invalid conversation identity");
            var args=new ArrayList<String>();args.add(executable);
            switch(this) {
                case CODEX -> {args.addAll(List.of("exec","--json","--sandbox","workspace-write"));if(session!=null)args.addAll(List.of("resume",session));args.add("-");}
                case CLAUDE -> {args.addAll(List.of("-p","--output-format","stream-json","--verbose","--include-partial-messages"));if(session!=null)args.addAll(List.of("--resume",session));}
                case OPENCODE -> {args.addAll(List.of("--standalone","run","--format","json"));if(session!=null)args.addAll(List.of("--session",session));}
            }
            return ManagedAgentTask.command(MiniJson.stringify(args));
        }
        @Override public String toString(){return title;}
    }
    record Update(String session,String text,String status,String error){}
    static final class Stream {
        private final Provider provider;
        private final StringBuilder pending=new StringBuilder(),partial=new StringBuilder();
        private final LinkedHashMap<String,String> messages=new LinkedHashMap<>();
        private String session,text="",status="Working…",error="";
        private boolean overflow;
        Stream(Provider provider,String expected){this.provider=provider;session=expected;}
        synchronized void accept(String chunk) {
            for(int i=0;i<chunk.length();i++) {
                char c=chunk.charAt(i);
                if(c=='\n'){if(!overflow)line(pending.toString());pending.setLength(0);overflow=false;}
                else if(pending.length()<131072)pending.append(c);
                else {overflow=true;error="CLI output exceeded the message limit.";}
            }
        }
        synchronized Update snapshot(){return new Update(session,text,status,error);}
        synchronized void finish(){if(!overflow&&!pending.isEmpty())line(pending.toString());pending.setLength(0);}
        private void identity(Object id){
            if(id==null)return;
            if(!(id instanceof String value)||!value.matches("[A-Za-z0-9_-]{1,128}")){error="CLI returned an invalid conversation ID.";return;}
            if(session!=null&&!session.equals(value)){error="CLI returned a different conversation. Start a new chat explicitly.";return;}
            session=value;
        }
        private void line(String raw){
            if(!raw.stripLeading().startsWith("{"))return;
            Map<String,Object> e;
            try{e=MiniJson.asObject(MiniJson.parseStrict(raw));}catch(RuntimeException invalid){error="CLI returned malformed JSON output.";return;}
            if(e==null)return;String type=Objects.toString(e.get("type"),"");
            if(provider==Provider.CODEX) {
                if(type.equals("thread.started"))identity(e.get("thread_id"));
                var item=object(e.get("item"));
                if("agent_message".equals(item.get("type"))&&Set.of("item.completed","item.updated").contains(type)){
                    String id=Objects.toString(item.get("id"),"answer");if(messages.size()>=64&&!messages.containsKey(id)){error="Too many response messages.";return;}
                    messages.put(id,bounded(item.get("text")));setText(String.join("\n\n",messages.values()));
                } else if(type.equals("item.started"))status="Using project tools…";
                if(type.equals("error")||type.equals("turn.failed"))error="CLI reported a failed turn.";
            } else if(provider==Provider.CLAUDE) {
                // Child-agent messages must not replace the main conversation or its reply.
                if(e.get("parent_tool_use_id")!=null)return;
                identity(e.get("session_id"));
                if(type.equals("stream_event")){
                    var delta=object(object(e.get("event")).get("delta"));
                    if("text_delta".equals(delta.get("type"))){if(partial.length()+bounded(delta.get("text")).length()>65536){error="Reply exceeded the text limit.";return;}partial.append(bounded(delta.get("text")));setText(partial.toString());}
                }
                if(type.equals("assistant")&&partial.isEmpty()){
                    Object content=object(e.get("message")).get("content");if(content instanceof List<?> blocks){var answer=new StringBuilder();for(Object block:blocks){var value=object(block);if("text".equals(value.get("type")))answer.append(bounded(value.get("text")));}setText(answer.toString());}
                }
                if(type.equals("result")){if(Boolean.TRUE.equals(e.get("is_error")))error="CLI reported a failed turn.";if(e.get("result") instanceof String result)setText(result);}
            } else {
                identity(e.get("sessionID"));
                if(type.equals("text")){String value=bounded(object(e.get("part")).get("text"));if(partial.length()+value.length()>65536){error="Reply exceeded the text limit.";return;}partial.append(value);setText(partial.toString());}
                if(type.equals("tool_use"))status="Using project tools…";
                if(type.equals("error"))error="CLI reported a failed turn.";
            }
        }
        private void setText(String value){if(value.length()>65536){error="Reply exceeded the text limit.";return;}text=value;status="Replying…";}
        private String bounded(Object value){if(value instanceof String text){if(text.length()>65536){error="Reply exceeded the text limit.";return "";}return text;}return "";}
        private static Map<String,Object> object(Object value){return value instanceof Map<?,?>?MiniJson.asObject(value):Map.of();}
    }
}
