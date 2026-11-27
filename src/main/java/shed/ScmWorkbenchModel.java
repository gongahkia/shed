package shed;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.*;
import java.util.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;

/** Structured working-copy data and direct argv contracts; no process or UI ownership. */
final class ScmWorkbenchModel {
    enum Provider {
        GIT("Git","git",".git"), MERCURIAL("Mercurial","hg",".hg"), SUBVERSION("Subversion","svn",".svn");
        final String label, executable, marker;
        Provider(String label,String executable,String marker){this.label=label;this.executable=executable;this.marker=marker;}
        @Override public String toString(){return label;}
    }
    enum Action { ADD, FORGET, REVERT, REMOVE, RENAME, RESOLVE, COMMIT, UPDATE, BRANCH, SWITCH, MERGE, SHELVE, UNSHELVE, PULL, PUSH }
    record Change(String path,String state,boolean conflict) { }
    record Revision(String id,String subject,String author,String date) { @Override public String toString(){return id+" · "+subject;} }
    record Branch(String id,String name) { @Override public String toString(){return name;} }
    record Snapshot(Provider provider,Path root,List<Change> changes,String location) { }
    static final int MAX_ROWS=10_000,MAX_OUTPUT=2*1024*1024;
    static List<Action> actions(Provider p){return switch(p){case GIT->List.of();case MERCURIAL->List.of(Action.ADD,Action.FORGET,Action.REVERT,Action.REMOVE,Action.RENAME,Action.RESOLVE,Action.COMMIT,Action.UPDATE,Action.BRANCH,Action.MERGE,Action.SHELVE,Action.UNSHELVE,Action.PULL,Action.PUSH);case SUBVERSION->List.of(Action.ADD,Action.REVERT,Action.REMOVE,Action.RENAME,Action.RESOLVE,Action.COMMIT,Action.UPDATE,Action.SWITCH,Action.BRANCH);};}
    static boolean network(Provider p,Action a){return a==Action.PULL||a==Action.PUSH||p==Provider.SUBVERSION&&(a==Action.COMMIT||a==Action.UPDATE||a==Action.SWITCH||a==Action.BRANCH);}
    static Path discover(Path start,Provider provider)throws IOException{
        if(start==null)return null;Path path=start.toAbsolutePath().normalize();
        if(!Files.isDirectory(path))return null;
        for(int depth=0;path!=null&&depth<64;depth++,path=path.getParent()){
            Path marker=path.resolve(provider.marker);
            if(Files.exists(marker,LinkOption.NOFOLLOW_LINKS)){
                if(Files.isSymbolicLink(marker)||!(Files.isDirectory(marker,LinkOption.NOFOLLOW_LINKS)||provider==Provider.GIT&&Files.isRegularFile(marker,LinkOption.NOFOLLOW_LINKS)))throw new IOException("Repository metadata must be a regular local marker");
                return path.toRealPath();
            }
        }return null;
    }
    static String path(String value)throws IOException{
        if(value==null||value.isBlank()||value.indexOf('\0')>=0||value.length()>8192||value.indexOf('\\')>=0)throw new IOException("Invalid repository-relative path");
        if(value.equals("."))return ".";
        Path p;try{p=Path.of(value);}catch(InvalidPathException bad){throw new IOException("Invalid repository-relative path",bad);}
        if(p.isAbsolute()||value.startsWith("-")||value.matches("^[A-Za-z]:.*")||p.toString().equals(".")||p.toString().isEmpty())throw new IOException("A file inside this repository is required");
        for(Path part:p)if(Set.of("..",".",".git",".hg",".svn").contains(part.toString().toLowerCase(Locale.ROOT)))throw new IOException("Repository metadata and traversal paths are not file targets");
        return p.toString().replace(java.io.File.separatorChar,'/');
    }
    static Path target(Path root,String relative,boolean mustExist)throws IOException{
        Path canonical=root.toRealPath(),candidate=canonical.resolve(path(relative)).normalize();
        if(!candidate.startsWith(canonical))throw new IOException("Path escapes repository");
        for(Path p=candidate;p!=null&&!p.equals(canonical);p=p.getParent()){
            if(Files.isSymbolicLink(p))throw new IOException("Linked file operations are not supported");
            if(Files.isDirectory(p,LinkOption.NOFOLLOW_LINKS))for(String marker:List.of(".git",".hg",".svn"))if(Files.exists(p.resolve(marker),LinkOption.NOFOLLOW_LINKS))throw new IOException("Nested repositories require selecting their own working-copy root");
        }
        if(mustExist&&!Files.exists(candidate,LinkOption.NOFOLLOW_LINKS))throw new IOException("Selected file no longer exists");
        return candidate;
    }
    static String operand(Provider provider,String path)throws IOException {String safe=path(path);return provider==Provider.SUBVERSION?safe+"@":"path:"+safe;}
    static List<String> command(Provider provider,String... args){
        if(provider==Provider.GIT)throw new IllegalArgumentException("Git keeps its existing controller");
        var values=new ArrayList<String>();values.add(provider.executable);
        if(provider==Provider.MERCURIAL)values.addAll(List.of("--noninteractive","--pager","never","--color","never","--encoding","UTF-8"));
        else values.addAll(List.of("--non-interactive","--no-auth-cache"));
        values.addAll(List.of(args));return List.copyOf(values);
    }
    static List<String> status(Provider p){return p==Provider.MERCURIAL?command(p,"status","--print0"):command(p,"status","--xml");}
    static List<String> history(Provider p){return p==Provider.MERCURIAL?command(p,"log","--limit","100","--template","{node}\\0{author}\\0{date|isodatesec}\\0{desc|firstline}\\0"):command(p,"log","--xml","--limit","100");}
    static List<String> diff(Provider p,String path)throws IOException{return p==Provider.MERCURIAL?command(p,"diff","--git","--",operand(p,path)):command(p,"diff","--internal-diff","--",operand(p,path));}
    static List<String> revisionDiff(Provider p,String revision)throws IOException{
        requireRevision(p,revision);return p==Provider.MERCURIAL?command(p,"export","--git","--rev",revision):command(p,"diff","--internal-diff","--change",revision);
    }
    static List<String> mutation(Provider p,Action a,String file,String argument)throws IOException {
        if(!actions(p).contains(a))throw new IOException("Action unavailable for this provider");
        String input=argument==null?"":argument;
        if(input.indexOf('\0')>=0||input.length()>8192)throw new IOException("Input exceeds its permitted size");
        boolean hg=p==Provider.MERCURIAL;
        return switch(a){
            case ADD -> command(p,"add","--",operand(p,file));
            case FORGET -> command(p,"forget","--",operand(p,file));
            case REVERT -> hg?command(p,"revert","--",operand(p,file)):command(p,"revert","--depth","empty","--",operand(p,file));
            case REMOVE -> command(p,hg?"remove":"delete","--",operand(p,file));
            case RENAME -> command(p,hg?"rename":"move","--",operand(p,file),path(input));
            case RESOLVE -> hg?command(p,"resolve","--mark","--",operand(p,file)):command(p,"resolve","--accept","working","--depth","empty","--",operand(p,file));
            case COMMIT -> {if(input.isBlank())throw new IOException("A commit message is required");yield command(p,"commit","--message",input);}
            case UPDATE -> {if(hg){requireRevision(p,input);yield command(p,"update","--rev",input);}if(!input.isBlank()){requireRevision(p,input);yield command(p,"update","--revision",input);}yield command(p,"update");}
            case BRANCH -> {
                if(!hg){int split=input.indexOf('\n');if(split<1||input.substring(split+1).isBlank())throw new IOException("A destination URL and branch/tag commit message are required");String url=input.substring(0,split);validateUrl(url);yield command(p,"copy","--message",input.substring(split+1),"--",".@",url);}
                if(input.isBlank()||input.startsWith("-")||input.codePoints().anyMatch(Character::isISOControl))throw new IOException("A branch name without control characters is required");yield command(p,"branch","--",input);
            }
            case SWITCH -> {validateUrl(input);yield command(p,"switch","--",input+"@",".");}
            case MERGE -> {requireRevision(p,input);yield command(p,"merge","--rev",input);}
            case SHELVE -> command(p,"shelve");
            case UNSHELVE -> command(p,"unshelve");
            case PULL -> command(p,"pull");
            case PUSH -> command(p,"push");
        };
    }
    static void validateUrl(String input)throws IOException{
        java.net.URI uri;
        try{uri=java.net.URI.create(input);}catch(IllegalArgumentException|NullPointerException bad){throw new IOException("Use a repository URL without embedded credentials, query or fragment",bad);}
        String scheme=uri.getScheme()==null?"":uri.getScheme().toLowerCase(Locale.ROOT);
        boolean local=scheme.equals("file");
        if(!Set.of("https","http","svn","svn+ssh","file").contains(scheme)||uri.isOpaque()||uri.getPath()==null||local&&!uri.getPath().startsWith("/")||!local&&(uri.getHost()==null||uri.getHost().isBlank())||uri.getUserInfo()!=null||uri.getFragment()!=null||uri.getQuery()!=null||input.codePoints().anyMatch(Character::isISOControl))throw new IOException("Use a repository URL without embedded credentials, query or fragment");
    }
    static void requireRevision(Provider p,String value)throws IOException{if(value==null||!(p==Provider.MERCURIAL?value.matches("[a-fA-F0-9]{40}"):value.matches("[0-9]{1,18}")))throw new IOException("Select a valid repository revision");}
    static List<Change> hgStatus(String output)throws IOException{
        var changes=new LinkedHashMap<String,Change>();
        if(!output.isEmpty()&&!output.endsWith("\0"))throw new IOException("Incomplete Mercurial status output");
        for(String row:output.split("\0",-1)){if(row.isEmpty())continue;if(row.length()<3||row.charAt(1)!=' ')throw new IOException("Invalid Mercurial status output");
            String state=switch(row.charAt(0)){case 'M'->"Modified";case 'A'->"Added";case 'R'->"Removed";case '!'->"Missing";case '?'->"Untracked";case 'I'->"Ignored";case 'C'->"Clean";default->throw new IOException("Unknown Mercurial status");};String path=path(row.substring(2));if(changes.put(path,new Change(path,state,false))!=null)throw new IOException("Duplicate status entry");if(changes.size()>MAX_ROWS)throw new IOException("Too many changed paths");}
        return List.copyOf(changes.values());
    }
    static List<Change> hgConflicts(List<Change> changes,String output)throws IOException{
        var conflicts=new HashSet<String>();if(!output.isEmpty()&&!output.endsWith("\0"))throw new IOException("Incomplete conflict output");for(String row:output.split("\0",-1)){if(row.isEmpty())continue;if(row.length()<3||row.charAt(1)!=' '||row.charAt(0)!='U'&&row.charAt(0)!='R')throw new IOException("Invalid conflict output");String path=path(row.substring(2));if(row.charAt(0)=='U')conflicts.add(path);}
        var result=new ArrayList<Change>();for(var c:changes)result.add(conflicts.remove(c.path())?new Change(c.path(),"Conflict",true):c);for(var path:conflicts)result.add(new Change(path,"Conflict",true));if(result.size()>MAX_ROWS)throw new IOException("Too many conflict paths");return List.copyOf(result);
    }
    static List<Change> svnStatus(String output)throws IOException{
        var doc=xml(output,"status");var nodes=doc.getElementsByTagName("entry");if(nodes.getLength()>MAX_ROWS)throw new IOException("Too many changed paths");var result=new ArrayList<Change>();var seen=new HashSet<String>();
        for(int i=0;i<nodes.getLength();i++){var entry=(Element)nodes.item(i);var statuses=entry.getElementsByTagName("wc-status");if(statuses.getLength()!=1)throw new IOException("Missing working-copy status");var status=(Element)statuses.item(0);String item=status.getAttribute("item"),props=status.getAttribute("props");boolean conflict=item.equals("conflicted")||props.equals("conflicted")||status.getAttribute("tree-conflicted").equals("true");
            if(Set.of("normal","none").contains(item)&&Set.of("none","normal").contains(props)&&!conflict)continue;
            String path=path(entry.getAttribute("path"));if(!seen.add(path))throw new IOException("Duplicate status entry");String label=switch(item){case "modified"->"Modified";case "added"->"Added";case "deleted"->"Removed";case "missing"->"Missing";case "unversioned"->"Untracked";case "replaced"->"Replaced";case "conflicted"->"Conflict";case "obstructed"->"Obstructed";case "external"->"External";case "ignored"->"Ignored";case "incomplete"->"Incomplete";case "normal","none"->"Properties modified";default->throw new IOException("Unknown Subversion status");};result.add(new Change(path,conflict?"Conflict":label,conflict));}
        return List.copyOf(result);
    }
    static List<Revision> hgHistory(String output)throws IOException{
        if(output.isEmpty())return List.of();if(!output.endsWith("\0"))throw new IOException("Incomplete history output");String[] fields=output.split("\0",-1);if((fields.length-1)%4!=0||(fields.length-1)/4>100)throw new IOException("Invalid history fields");var result=new ArrayList<Revision>();for(int i=0;i<fields.length-1;i+=4){requireRevision(Provider.MERCURIAL,fields[i]);result.add(new Revision(fields[i],fields[i+3],fields[i+1],fields[i+2]));}return List.copyOf(result);
    }
    static List<Revision> svnHistory(String output)throws IOException{
        var doc=xml(output,"log");var entries=doc.getElementsByTagName("logentry");if(entries.getLength()>100)throw new IOException("Too many history entries");var result=new ArrayList<Revision>();for(int i=0;i<entries.getLength();i++){var entry=(Element)entries.item(i);String id=entry.getAttribute("revision");requireRevision(Provider.SUBVERSION,id);result.add(new Revision(id,text(entry,"msg").lines().findFirst().orElse(""),text(entry,"author"),text(entry,"date")));}return List.copyOf(result);
    }
    static List<Branch> hgBranches(String output)throws IOException{
        if(!output.isEmpty()&&!output.endsWith("\0"))throw new IOException("Incomplete branch output");String[] fields=output.split("\0",-1);if((fields.length-1)%2!=0||(fields.length-1)/2>1000)throw new IOException("Invalid branch output");var result=new ArrayList<Branch>();for(int i=0;i<fields.length-1;i+=2){requireRevision(Provider.MERCURIAL,fields[i]);result.add(new Branch(fields[i],fields[i+1]));}return List.copyOf(result);
    }
    static String svnLocation(String output)throws IOException{return text(xml(output,"info").getDocumentElement(),"url").replaceAll("://[^/@]+@","://[redacted]@");}
    private static org.w3c.dom.Document xml(String source,String root)throws IOException{
        if(source.length()>MAX_OUTPUT)throw new IOException("XML exceeds the output budget");
        try{var factory=DocumentBuilderFactory.newInstance();factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);factory.setFeature("http://xml.org/sax/features/external-general-entities",false);factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false);factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,"");factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");factory.setXIncludeAware(false);factory.setExpandEntityReferences(false);var builder=factory.newDocumentBuilder();builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler(){@Override public void fatalError(org.xml.sax.SAXParseException e)throws org.xml.sax.SAXException{throw e;}});var doc=builder.parse(new InputSource(new StringReader(source)));if(!doc.getDocumentElement().getTagName().equals(root))throw new IOException("Unexpected XML document");return doc;}catch(Exception error){throw new IOException("Invalid or unsafe Subversion XML",error);}
    }
    private static String text(Element e,String tag){var nodes=e.getElementsByTagName(tag);return nodes.getLength()==0?"":nodes.item(0).getTextContent();}
}
