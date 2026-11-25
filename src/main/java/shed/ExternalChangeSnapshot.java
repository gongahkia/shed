package shed;

import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.util.*;

/** Disposable observation snapshots; never installed as editable documents or written to disk. */
final class ExternalChangeSnapshot {
    static final int MAX_FILES=16384, MAX_FILE=1024*1024, MAX_TOTAL=32*1024*1024;
    private static final Set<String> OPAQUE_EXTENSIONS=Set.of(".png",".jpg",".jpeg",".gif",".webp",".bmp",".tif",".tiff",".ico",".icns",".pdf",".zip",".gz",".tgz",".bz2",".xz",".7z",".jar",".class",".ttf",".otf",".woff",".woff2",".mp3",".mp4",".mov",".wav");
    record Identity(Object key,java.nio.file.attribute.FileTime created){}
    record File(String text,String digest,boolean textual,Identity identity) {
        File(String text,String digest,boolean textual){this(text,digest,textual,null);}
    }
    static final String COVERAGE="Source and safe config files; binary assets tracked by metadata; private/generated paths excluded";
    private static final Set<String> CONFIG_FILES=Set.of(".gitignore",".gitattributes",".gitmodules",".editorconfig",".dockerignore",".gitlab-ci.yml",".prettierrc",".prettierrc.json",".prettierrc.yaml",".eslintrc",".eslintrc.json",".eslintrc.yml",".stylelintrc",".clang-format",".clang-tidy",".nvmrc",".python-version",".tool-versions");
    private static final Set<String> CONFIG_DIRECTORIES=Set.of(".github",".changeset");
    record Change(String kind,String path,String previous,String before,String after,List<WorkspaceTextEdits.Hunk> hunks) {
        int added(){return hunks.stream().mapToInt(h->lines(display(h).after())).sum();}
        int removed(){return hunks.stream().mapToInt(h->lines(display(h).before())).sum();}
        Display display(WorkspaceTextEdits.Hunk h){
            int start=before.lastIndexOf('\n',Math.max(-1,h.start()-1))+1,end;
            if(h.end()>0&&before.charAt(h.end()-1)=='\n')end=h.end();else {int newline=before.indexOf('\n',h.end());end=newline<0?before.length():newline+1;}
            if(h.start()==h.end()&&(h.start()==0||before.charAt(h.start()-1)=='\n')&&h.replacement().endsWith("\n"))end=h.end();
            return new Display(start,end,before.substring(start,end),before.substring(start,h.start())+h.replacement()+before.substring(h.end(),end));
        }
        private static int lines(String text){return text.isEmpty()?0:(int)text.chars().filter(c->c=='\n').count()+(text.endsWith("\n")?0:1);}
        @Override public String toString(){return kind+"  "+(previous==null?path:previous+" → "+path)+(hunks.isEmpty()&&before.isEmpty()&&after.isEmpty()?"":"  +"+added()+" −"+removed());}
    }
    record Display(int start,int end,String before,String after){}
    static Map<String,File> capture(Path root) throws IOException {return capture(root,List.of());}
    static Map<String,File> capture(Path root,List<Path> excludedRoots) throws IOException {
        var result=new TreeMap<String,File>();long[] bytes={0};long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        Files.walkFileTree(root,Set.of(),32,new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir,BasicFileAttributes a)throws IOException {checkBudget();return allowed(root,dir)&&excludedRoots.stream().noneMatch(dir::startsWith)?FileVisitResult.CONTINUE:FileVisitResult.SKIP_SUBTREE;}
            @Override public FileVisitResult visitFile(Path file,BasicFileAttributes a)throws IOException {
                checkBudget();
                if(!allowed(root,file)||!a.isRegularFile()||a.isSymbolicLink())return FileVisitResult.CONTINUE;
                if(result.size()>=MAX_FILES)throw new IOException("Watch snapshot limit reached; choose a smaller project");
                String key=root.relativize(file).toString();
                if(a.size()>MAX_FILE){result.put(key,new File("[File exceeds the 1 MiB visual diff limit]","large:"+a.size()+":"+a.lastModifiedTime(),false));return FileVisitResult.CONTINUE;}
                if(OPAQUE_EXTENSIONS.contains(extension(key).toLowerCase(Locale.ROOT))){
                    // binary comparisons are deferred; do not spend the bounded source budget reading assets
                    result.put(key,new File("[Binary or unsupported encoding; metadata-only observation]","opaque:"+a.fileKey()+":"+a.creationTime()+":"+a.lastModifiedTime()+":"+a.size(),false,a.fileKey()==null?null:new Identity(a.fileKey(),a.creationTime())));
                    return FileVisitResult.CONTINUE;
                }
                byte[] data;try(var input=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)){data=input.readNBytes(MAX_FILE+1);}
                bytes[0]+=data.length;if(data.length>MAX_FILE||bytes[0]>MAX_TOTAL)throw new IOException("Watch snapshot byte limit reached");
                boolean text=true;String content;
                try {content=StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(data)).toString();if(content.indexOf('\0')>=0)text=false;}
                catch(java.nio.charset.CharacterCodingException binary){content="";text=false;}
                result.put(key,new File(text?content:"[Binary or unsupported encoding]",WorkspacePaths.hash(data),text,a.fileKey()==null?null:new Identity(a.fileKey(),a.creationTime())));return FileVisitResult.CONTINUE;
            }
            private void checkBudget()throws IOException {
                if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException("Watch snapshot cancelled");
                if(System.nanoTime()>deadline)throw new IOException("Watch snapshot time limit reached; choose a smaller project");
            }
        });return Map.copyOf(result);
    }
    static boolean safeHiddenName(String name){String value=name.toLowerCase(Locale.ROOT);return CONFIG_FILES.contains(value)||CONFIG_DIRECTORIES.contains(value);}
    static boolean allowed(Path root,Path candidate){
        if(!TestWatchService.watches(root,candidate))return false;
        Path relative=root.relativize(candidate);int index=0;
        for(Path part:relative){String name=part.toString().toLowerCase(Locale.ROOT);index++;
            if(name.equals("vendor"))return false;
            if(name.startsWith(".")&&!CONFIG_DIRECTORIES.contains(name)&&!(index==relative.getNameCount()&&CONFIG_FILES.contains(name)))return false;
        }
        String name=candidate.getFileName().toString().toLowerCase(Locale.ROOT);
        return !name.endsWith(".env")&&!name.contains(".env.")&&!name.endsWith(".pem")&&!name.endsWith(".key")&&!name.endsWith(".p12")&&!name.endsWith(".pfx")
            &&!Set.of("auth.json","credentials.json","id_rsa","id_ed25519").contains(name);
    }

    static List<Change> compare(Map<String,File> base,Map<String,File> now){
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        var rows=new ArrayList<Change>();var names=new TreeSet<String>();names.addAll(base.keySet());names.addAll(now.keySet());
        var deleted=new HashSet<String>();
        var moves=moveCandidates(base,now);
        for(String name:names){if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException("Watch diff cancelled");if(System.nanoTime()>deadline)throw new WorkspaceApiException(WorkspaceApiException.Code.LIMIT_EXCEEDED,"Diff computation limit reached; choose a smaller project");var left=base.get(name);var right=now.get(name);if(left!=null&&right!=null&&left.digest().equals(right.digest()))continue;
            if(left==null){var move=moves.get(name);String previous=move==null?null:move.previous();
                if(previous!=null){deleted.add(previous);left=base.get(previous);}
                rows.add(change(move==null?"A":move.kind(),name,previous,left,right));
            } else if(right==null)rows.add(change("D",name,null,left,null));else rows.add(change("M",name,null,left,right));
        }
        rows.removeIf(row->row.kind().equals("D")&&deleted.contains(row.path()));return List.copyOf(rows);
    }
    private record Move(String previous,String kind){}
    private static Map<String,Move> moveCandidates(Map<String,File> base,Map<String,File> now){
        var removed=base.keySet().stream().filter(path->!now.containsKey(path)).toList();
        var added=now.keySet().stream().filter(path->!base.containsKey(path)).toList();var moves=new HashMap<String,Move>();
        var identities=new HashMap<Identity,List<String>>();var targets=new HashMap<Identity,Integer>();
        for(String path:removed){var id=base.get(path).identity();if(id!=null)identities.computeIfAbsent(id,key->new ArrayList<>()).add(path);}
        for(String path:added){var id=now.get(path).identity();if(id!=null)targets.merge(id,1,Integer::sum);}
        for(String path:added){var id=now.get(path).identity();var sources=id==null?List.<String>of():identities.getOrDefault(id,List.of());if(sources.size()==1&&targets.get(id)==1)moves.put(path,new Move(sources.getFirst(),"R"));}
        var exactSources=new HashMap<String,List<String>>();var exactTargets=new HashMap<String,List<String>>();
        var used=new HashSet<String>();moves.values().forEach(move->used.add(move.previous()));
        for(String path:removed)if(!used.contains(path)&&contentDigest(base.get(path)))exactSources.computeIfAbsent(base.get(path).digest(),key->new ArrayList<>()).add(path);
        for(String path:added)if(!moves.containsKey(path)&&contentDigest(now.get(path)))exactTargets.computeIfAbsent(now.get(path).digest(),key->new ArrayList<>()).add(path);
        exactTargets.forEach((digest,paths)->{var sources=exactSources.getOrDefault(digest,List.of());if(paths.size()==1&&sources.size()==1)moves.put(paths.getFirst(),new Move(sources.getFirst(),"R"));});
        // similarity is advisory, bounded and reciprocal; ambiguous pairs remain add/delete
        if((long)removed.size()*added.size()>4096)return moves;
        var scores=new HashMap<String,Map<String,Integer>>();long work=0;
        for(String target:added){if(moves.containsKey(target))continue;var candidates=new HashMap<String,Integer>();
            for(String source:removed){if(moves.values().stream().anyMatch(move->move.previous().equals(source)))continue;
                var a=base.get(source);var b=now.get(target);if(!a.textual()||!b.textual()||a.text().length()>65536||b.text().length()>65536||!extension(source).equals(extension(target)))continue;
                work+=(long)a.text().length()+b.text().length();if(work>4*1024*1024||Thread.currentThread().isInterrupted())return moves;
                int score=similarity(a.text(),b.text());if(score>=75)candidates.put(source,score);
            }scores.put(target,candidates);
        }
        for(var target:scores.entrySet()){
            var order=target.getValue().entrySet().stream().sorted(Map.Entry.<String,Integer>comparingByValue().reversed()).toList();
            if(order.isEmpty()||order.size()>1&&order.getFirst().getValue()-order.get(1).getValue()<10)continue;
            var winner=order.getFirst();int alternate=scores.entrySet().stream().filter(entry->!entry.getKey().equals(target.getKey())).mapToInt(entry->entry.getValue().getOrDefault(winner.getKey(),0)).max().orElse(0);
            if(winner.getValue()-alternate>=10)moves.put(target.getKey(),new Move(winner.getKey(),"R?"));
        }return moves;
    }
    private static boolean contentDigest(File file){return !file.digest().startsWith("large:")&&!file.digest().startsWith("opaque:");}
    private static String extension(String path){String name=Path.of(path).getFileName().toString();int dot=name.lastIndexOf('.');return dot<0?"":name.substring(dot);}
    private static int similarity(String a,String b){
        var left=a.lines().toList();var right=b.lines().toList();if(left.size()<4||right.size()<4)return 0;
        var counts=new HashMap<String,Integer>();for(String line:left)if(!line.isBlank())counts.merge(line,1,Integer::sum);
        long common=0;for(String line:right){int count=counts.getOrDefault(line,0);if(count>0){common+=line.length()+1;counts.put(line,count-1);}}
        return (int)(200*common/Math.max(1L,(long)a.length()+b.length()));
    }
    static String moveSummary(List<Change> changes){
        var groups=new TreeMap<String,Integer>();for(var row:changes)if(row.previous()!=null){Path from=Path.of(row.previous()).getParent(),to=Path.of(row.path()).getParent();if(!Objects.equals(from,to))groups.merge((row.kind().equals("R?")?"Possible move: ":"Move: ")+Objects.toString(from,".")+"/ → "+Objects.toString(to,".")+"/",1,Integer::sum);}
        return groups.entrySet().stream().filter(entry->entry.getValue()>1).limit(3).map(entry->entry.getKey()+" ("+entry.getValue()+" files)").collect(java.util.stream.Collectors.joining(" · "));
    }
    private static Change change(String kind,String path,String previous,File before,File after){
        String a=before==null?"":before.text(),b=after==null?"":after.text();
        var hunks=(before==null||before.textual())&&(after==null||after.textual())?WorkspaceTextEdits.diff(a,b):List.<WorkspaceTextEdits.Hunk>of();
        return new Change(kind,path,previous,a,b,hunks);
    }
}
