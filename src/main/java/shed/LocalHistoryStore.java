package shed;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Bounded owner-private saved source versions. Disk work belongs to the existing save worker. */
final class LocalHistoryStore {
    static final int MAX_UNITS = 524_288, PER_FILE = 20, MAX_VERSIONS = 256;
    static final long MAX_BYTES = 32L * 1024 * 1024;
    record Version(String id, long millis, int units) { }
    private final Path root;
    private final java.util.function.BooleanSupplier enabled;
    LocalHistoryStore(Path root) { this(root, () -> true); }
    LocalHistoryStore(Path root, java.util.function.BooleanSupplier enabled) { this.root = root.toAbsolutePath().normalize(); this.enabled=enabled; }
    private String key(Path file) throws IOException {
        String name=file.getFileName().toString().toLowerCase(Locale.ROOT);
        if(name.startsWith(".") || name.contains("credential") || name.contains("secret") || name.endsWith(".pem") || name.endsWith(".key"))
            throw new IOException("Sensitive or hidden files are excluded from local history");
        if(Files.isSymbolicLink(file))throw new IOException("Symlink sources are excluded from local history");
        return hash(file.toAbsolutePath().normalize().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    synchronized void record(Path file,String content) throws IOException {
        if(!enabled.getAsBoolean() || content.length()>MAX_UNITS) return;
        verifyRoot();String key=key(file);PrivateWorkspaceFiles.directory(root);
        var all=scan();
        Path directory=PrivateWorkspaceFiles.directory(root.resolve(key));
        byte[] bytes=encode(content);var existing=versions(file);
        if(!existing.isEmpty()&&Arrays.equals(PrivateWorkspaceFiles.read(directory.resolve(existing.getFirst().id()),MAX_UNITS*2),bytes))return;
        String id=String.format(Locale.ROOT,"%019d-%s",Math.max(System.currentTimeMillis(),existing.isEmpty()?0:existing.getFirst().millis()+1),UUID.randomUUID())+"-"+hash(bytes)+".utf16";
        PrivateWorkspaceFiles.create(directory,id,bytes);
        all=scan();var local=all.stream().filter(p->p.getParent().equals(directory)).sorted(Comparator.reverseOrder()).toList();
        for(int i=PER_FILE;i<local.size();i++)remove(local.get(i));
        all=scan();long total=0;for(Path p:all)total+=Files.size(p);
        int remaining=all.size();for(Path p:all){if(total<=MAX_BYTES&&remaining<=MAX_VERSIONS)break;long size=Files.size(p);remove(p);total-=size;remaining--;}
        try(var dirs=Files.newDirectoryStream(root)){for(Path dir:dirs){PrivateWorkspaceFiles.existingDirectory(dir);try(var children=Files.newDirectoryStream(dir)){if(!children.iterator().hasNext())Files.delete(dir);}}}
    }
    synchronized List<Version> versions(Path file) throws IOException {
        verifyRoot();Path directory=root.resolve(key(file));
        if(!Files.exists(root,LinkOption.NOFOLLOW_LINKS))return List.of();
        PrivateWorkspaceFiles.existingDirectory(root);
        if(!Files.exists(directory,LinkOption.NOFOLLOW_LINKS))return List.of();
        PrivateWorkspaceFiles.existingDirectory(directory);var result=new ArrayList<Version>();
        try(var files=Files.newDirectoryStream(directory)){for(Path path:files){validate(path);if(result.size()>=MAX_VERSIONS)throw new IOException("Local history entry limit exceeded");result.add(new Version(path.getFileName().toString(),Long.parseLong(path.getFileName().toString().substring(0,19)),(int)Files.size(path)/2));}}
        return result.stream().sorted(Comparator.comparing(Version::id).reversed()).toList();
    }
    synchronized String read(Path file,String id) throws IOException {
        if(!validName(id))throw new IOException("Invalid history identity");
        verifyRoot();Path directory=root.resolve(key(file));PrivateWorkspaceFiles.existingDirectory(root);PrivateWorkspaceFiles.existingDirectory(directory);
        byte[] bytes=PrivateWorkspaceFiles.read(directory.resolve(id),MAX_UNITS*2);
        if(!hash(bytes).equals(id.substring(57,121)))throw new IOException("Local history integrity check failed");
        if(bytes.length%2!=0)throw new IOException("Incomplete local history version");
        var buffer=ByteBuffer.wrap(bytes);var text=new StringBuilder(bytes.length/2);while(buffer.hasRemaining())text.append(buffer.getChar());return text.toString();
    }
    synchronized void clear(Path file) throws IOException {for(var version:versions(file))remove(root.resolve(key(file)).resolve(version.id()));}
    private void verifyRoot() throws IOException { for(Path path=root;path!=null;path=path.getParent())if(Files.isSymbolicLink(path))throw new IOException("Local history directory must not traverse symlinks"); }
    private List<Path> scan()throws IOException {
        var result=new ArrayList<Path>();int count=0;
        try(var dirs=Files.newDirectoryStream(root)){for(Path dir:dirs){if(++count>512||!dir.getFileName().toString().matches("[a-f0-9]{64}"))throw new IOException("Unexpected local history directory");PrivateWorkspaceFiles.existingDirectory(dir);try(var files=Files.newDirectoryStream(dir)){for(Path file:files){validate(file);if(result.size()>=4096)throw new IOException("Local history scan limit exceeded");result.add(file);}}}}
        result.sort(Comparator.comparing(p->p.getFileName().toString()));return result;
    }
    private static boolean validName(String name){return name!=null&&name.matches("[0-9]{19}-[a-f0-9-]{36}-[a-f0-9]{64}\\.utf16");}
    private static void validate(Path file)throws IOException{if(!validName(file.getFileName().toString()))throw new IOException("Unexpected local history file");PrivateWorkspaceFiles.checkFile(file);if(Files.size(file)>MAX_UNITS*2||Files.size(file)%2!=0)throw new IOException("Invalid local history size");}
    private static void remove(Path file)throws IOException{validate(file);PrivateWorkspaceFiles.deleteIfMatching(file,PrivateWorkspaceFiles.read(file,MAX_UNITS*2));}
    private static byte[] encode(String value){var bytes=ByteBuffer.allocate(value.length()*2);for(int i=0;i<value.length();i++)bytes.putChar(value.charAt(i));return bytes.array();}
    private static String hash(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
}
