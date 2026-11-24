package shed;

import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.util.*;

/** Disposable observation snapshots; never installed as editable documents or written to disk. */
final class ExternalChangeSnapshot {
    static final int MAX_FILES=4096, MAX_FILE=65536, MAX_TOTAL=16*1024*1024;
    record File(String text,String digest,boolean textual) { }
    record Change(String kind,String path,String previous,String before,String after,List<WorkspaceTextEdits.Hunk> hunks) {
        int added(){return hunks.stream().mapToInt(h->lines(h.replacement())).sum();}
        int removed(){return hunks.stream().mapToInt(h->lines(h.before())).sum();}
        private static int lines(String text){return text.isEmpty()?0:(int)text.chars().filter(c->c=='\n').count()+(text.endsWith("\n")?0:1);}
        @Override public String toString(){return kind+"  "+path+(hunks.isEmpty()&&before.isEmpty()&&after.isEmpty()?"":"  +"+added()+" −"+removed());}
    }
    static Map<String,File> capture(Path root) throws IOException {return capture(root,List.of());}
    static Map<String,File> capture(Path root,List<Path> excludedRoots) throws IOException {
        var result=new TreeMap<String,File>();long[] bytes={0};long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        Files.walkFileTree(root,Set.of(),32,new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir,BasicFileAttributes a){return allowed(root,dir)&&excludedRoots.stream().noneMatch(dir::startsWith)?FileVisitResult.CONTINUE:FileVisitResult.SKIP_SUBTREE;}
            @Override public FileVisitResult visitFile(Path file,BasicFileAttributes a)throws IOException {
                if(!allowed(root,file)||!a.isRegularFile()||a.isSymbolicLink())return FileVisitResult.CONTINUE;
                if(result.size()>=MAX_FILES||System.nanoTime()>deadline)throw new IOException("Watch snapshot limit reached; choose a smaller project");
                String key=root.relativize(file).toString();
                if(a.size()>MAX_FILE){result.put(key,new File("[File exceeds the 64 KiB visual diff limit]","large:"+a.size()+":"+a.lastModifiedTime(),false));return FileVisitResult.CONTINUE;}
                byte[] data;try(var input=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)){data=input.readNBytes(MAX_FILE+1);}
                bytes[0]+=data.length;if(data.length>MAX_FILE||bytes[0]>MAX_TOTAL)throw new IOException("Watch snapshot byte limit reached");
                boolean text=true;String content;
                try {content=StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(data)).toString();if(content.indexOf('\0')>=0)text=false;}
                catch(java.nio.charset.CharacterCodingException binary){content="";text=false;}
                result.put(key,new File(text?content:"[Binary or unsupported encoding]",WorkspacePaths.hash(data),text));return FileVisitResult.CONTINUE;
            }
        });return Map.copyOf(result);
    }
    static boolean allowed(Path root,Path candidate){
        if(!TestWatchService.watches(root,candidate))return false;
        for(Path part:root.relativize(candidate)) {String p=part.toString();if(p.startsWith(".")||p.equals("vendor"))return false;}
        String name=candidate.getFileName().toString().toLowerCase(Locale.ROOT);
        return !name.endsWith(".env")&&!name.contains(".env.")&&!name.endsWith(".pem")&&!name.endsWith(".key");
    }
    static List<Change> compare(Map<String,File> base,Map<String,File> now){
        var rows=new ArrayList<Change>();var names=new TreeSet<String>();names.addAll(base.keySet());names.addAll(now.keySet());
        var deleted=new HashSet<String>();
        for(String name:names){var left=base.get(name);var right=now.get(name);if(left!=null&&right!=null&&left.digest().equals(right.digest()))continue;
            if(left==null){String previous=null;
                var matches=base.entrySet().stream().filter(e->!now.containsKey(e.getKey())&&e.getValue().digest().equals(right.digest())).toList();
                long destinations=now.entrySet().stream().filter(e->!base.containsKey(e.getKey())&&e.getValue().digest().equals(right.digest())).count();
                if(!right.digest().startsWith("large:")&&matches.size()==1&&destinations==1){previous=matches.getFirst().getKey();deleted.add(previous);left=base.get(previous);}
                rows.add(change(previous==null?"A":"R",name,previous,left,right));
            } else if(right==null)rows.add(change("D",name,null,left,null));else rows.add(change("M",name,null,left,right));
        }
        rows.removeIf(row->row.kind().equals("D")&&deleted.contains(row.path()));return List.copyOf(rows);
    }
    private static Change change(String kind,String path,String previous,File before,File after){
        String a=before==null?"":before.text(),b=after==null?"":after.text();
        var hunks=(before==null||before.textual())&&(after==null||after.textual())?WorkspaceTextEdits.diff(a,b):List.<WorkspaceTextEdits.Hunk>of();
        return new Change(kind,path,previous,a,b,hunks);
    }
}
