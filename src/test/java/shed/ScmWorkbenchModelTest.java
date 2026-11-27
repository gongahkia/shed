package shed;

import static org.junit.jupiter.api.Assertions.*;
import static shed.ScmWorkbenchModel.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScmWorkbenchModelTest {
    @TempDir Path root;
    private static final String NODE="a".repeat(40);
    @Test void mercurialStatusUsesNulRecordsAndPreservesUnusualNames()throws Exception{
        var rows=hgStatus("M hello world.txt\0A 中文\nfile.txt\0R old.txt\0? glob:literal[1]\0");assertEquals(4,rows.size());assertEquals("中文\nfile.txt",rows.get(1).path());assertEquals("Removed",rows.get(2).state());
        var conflicts=hgConflicts(rows,"U hello world.txt\0R old.txt\0");assertTrue(conflicts.getFirst().conflict());assertEquals("Conflict",conflicts.getFirst().state());assertFalse(conflicts.get(2).conflict());
        assertThrows(IOException.class,()->hgStatus("M incomplete"));assertThrows(IOException.class,()->hgStatus("Z unknown\0"));assertThrows(IOException.class,()->hgStatus("M ../escape\0"));assertThrows(IOException.class,()->hgStatus("M same\0M same\0"));
    }
    @Test void subversionParsesPropertiesRootCopiesMissingAndTreeConflicts()throws Exception{
        String xml="<status><target path='.'><entry path='.'><wc-status item='normal' props='modified'/></entry><entry path='file@name'><wc-status item='modified' props='none'/></entry><entry path='missing.txt'><wc-status item='missing' props='none'/></entry><entry path='added'><wc-status item='added' props='none' copied='true'/></entry><entry path='conflict'><wc-status item='normal' props='none' tree-conflicted='true'/></entry></target></status>";
        var rows=svnStatus(xml);assertEquals(5,rows.size());assertEquals("Properties modified",rows.getFirst().state());assertEquals("file@name",rows.get(1).path());assertTrue(rows.getLast().conflict());
        assertThrows(IOException.class,()->svnStatus("<log/>"));assertThrows(IOException.class,()->svnStatus("<status><entry path='../escape'><wc-status item='modified' props='none'/></entry></status>"));
    }
    @Test void xmlRejectsExternalEntitiesAndMalformedOrOversizedData()throws Exception{
        Path sentinel=root.resolve("synthetic");Files.writeString(sentinel,"PRIVATE SENTINEL");String evil="<!DOCTYPE status [<!ENTITY xxe SYSTEM '"+sentinel.toUri()+"'>]><status>&xxe;</status>";
        assertThrows(IOException.class,()->svnStatus(evil));assertThrows(IOException.class,()->svnStatus("<status>"));assertThrows(IOException.class,()->svnStatus("x".repeat(MAX_OUTPUT+1)));assertEquals("PRIVATE SENTINEL",Files.readString(sentinel));
    }
    @Test void historyAndBranchesHaveBoundedValidatedIdentities()throws Exception{
        var hg=hgHistory(NODE+"\0User\0"+"2026-01-01\0subject\0");assertEquals(NODE,hg.getFirst().id());assertEquals("subject",hg.getFirst().subject());
        var branches=hgBranches(NODE+"\0feature name\0");assertEquals("feature name",branches.getFirst().name());
        var svn=svnHistory("<log><logentry revision='12'><author>User</author><date>2026</date><msg>&lt;html&gt;subject\nbody</msg></logentry></log>");assertEquals("12",svn.getFirst().id());assertEquals("<html>subject",svn.getFirst().subject());
        assertThrows(IOException.class,()->hgHistory("bad\0User\0Date\0Subject\0"));assertThrows(IOException.class,()->hgHistory(NODE+"\0partial"));assertThrows(IOException.class,()->svnHistory("<log><logentry revision='--bad'/></log>"));
    }
    @Test void commandOperandsAreLiteralAndNeverShellInterpolated()throws Exception{
        var hg=diff(Provider.MERCURIAL,"glob:my [file].txt");assertEquals("path:glob:my [file].txt",hg.getLast());assertTrue(hg.contains("--"));
        var svn=diff(Provider.SUBVERSION,"file@HEAD");assertEquals("file@HEAD@",svn.getLast());assertTrue(svn.contains("--internal-diff"));
        String message="--amend; $(touch bad)\nsecond line";var commit=mutation(Provider.MERCURIAL,Action.COMMIT,null,message);assertEquals(message,commit.getLast());assertEquals(1,commit.stream().filter(v->v.equals(message)).count());
        var update=mutation(Provider.SUBVERSION,Action.UPDATE,null,"42");assertEquals(List.of("--revision","42"),update.subList(update.size()-2,update.size()));
        assertThrows(IOException.class,()->mutation(Provider.SUBVERSION,Action.PUSH,null,""));assertThrows(IOException.class,()->revisionDiff(Provider.MERCURIAL,"all()"));assertThrows(IOException.class,()->mutation(Provider.MERCURIAL,Action.MERGE,null,"--force"));
    }
    @Test void unsafePathsAndCredentialBearingUrlsFailBeforeExecution()throws Exception{
        for(String p:List.of("../file","/etc/passwd","C:/private",".hg/store","x/../file","x/.svn/wc.db","-R","a\\b"))assertThrows(IOException.class,()->path(p),p);
        validateUrl("https://example.invalid/repo/branch");validateUrl("https://example.invalid");validateUrl("HTTPS://example.invalid/repo");assertThrows(IOException.class,()->validateUrl(null));validateUrl("file:///tmp/repo/branch");
        for(String url:List.of("https://user:secret@example.invalid/repo","http://example.invalid/repo?token=secret","ssh://example.invalid/repo","not a url","relative","","https:/missing-host","file:relative"))assertThrows(IOException.class,()->validateUrl(url));
        Path outside=Files.createDirectory(root.resolve("outside")),repo=Files.createDirectory(root.resolve("repo"));Files.createSymbolicLink(repo.resolve("alias"),outside);
        assertThrows(IOException.class,()->target(repo,"alias/file",false));Files.createDirectories(repo.resolve("nested/.hg"));assertThrows(IOException.class,()->target(repo,"nested/file",false));assertEquals(repo.resolve("safe.txt"),target(repo,"safe.txt",false));
    }
    @Test void detectionFindsWorkingCopyAndRejectsLinkedMetadata()throws Exception{
        Path repo=Files.createDirectories(root.resolve("repo/.hg")).getParent();Path child=Files.createDirectory(repo.resolve("sub"));assertEquals(repo.toRealPath(),discover(child,Provider.MERCURIAL));assertNull(discover(child,Provider.SUBVERSION));
        Path other=Files.createDirectory(root.resolve("other"));Files.createSymbolicLink(other.resolve(".svn"),repo.resolve(".hg"));assertThrows(IOException.class,()->discover(other,Provider.SUBVERSION));
    }
    @Test void subversionBranchTagAndSwitchRespectNativeSemantics()throws Exception{
        var branch=mutation(Provider.SUBVERSION,Action.BRANCH,null,"https://example.invalid/repo/branches/new\nCreate feature branch");assertTrue(branch.contains("copy"));assertTrue(branch.contains("--message"));assertEquals("https://example.invalid/repo/branches/new",branch.getLast());
        var sw=mutation(Provider.SUBVERSION,Action.SWITCH,null,"https://example.invalid/repo/trunk");assertEquals(".",sw.getLast());assertTrue(sw.contains("https://example.invalid/repo/trunk@"));
        assertThrows(IOException.class,()->mutation(Provider.SUBVERSION,Action.BRANCH,null,"https://example.invalid/repo/branch\n"));
    }
    @Test void everyExposedActionHasAnExplicitProviderSpecificArgvContract()throws Exception{
        for(var provider:List.of(Provider.MERCURIAL,Provider.SUBVERSION)){
            boolean hg=provider==Provider.MERCURIAL;String operand=hg?"path:file.txt":"file.txt@";
            for(var action:actions(provider)){
                String value=switch(action){case COMMIT->"Save changes";case RENAME->"renamed.txt";case BRANCH->hg?"feature":"https://example.invalid/branches/feature\nCreate branch";case SWITCH->"https://example.invalid/trunk";case UPDATE,MERGE->hg?NODE:"12";default->"";};
                List<String> expected=switch(action){
                    case ADD->List.of("add","--",operand);case FORGET->List.of("forget","--",operand);
                    case REVERT->hg?List.of("revert","--",operand):List.of("revert","--depth","empty","--",operand);
                    case REMOVE->List.of(hg?"remove":"delete","--",operand);case RENAME->List.of(hg?"rename":"move","--",operand,"renamed.txt");
                    case RESOLVE->hg?List.of("resolve","--mark","--",operand):List.of("resolve","--accept","working","--depth","empty","--",operand);
                    case COMMIT->List.of("commit","--message","Save changes");case UPDATE->List.of("update",hg?"--rev":"--revision",hg?NODE:"12");
                    case BRANCH->hg?List.of("branch","--","feature"):List.of("copy","--message","Create branch","--",".@","https://example.invalid/branches/feature");
                    case SWITCH->List.of("switch","--","https://example.invalid/trunk@",".");case MERGE->List.of("merge","--rev",NODE);
                    case SHELVE->List.of("shelve");case UNSHELVE->List.of("unshelve");case PULL->List.of("pull");case PUSH->List.of("push");
                };
                var actual=mutation(provider,action,"file.txt",value);assertEquals(expected,actual.subList(hg?8:3,actual.size()),provider+" "+action);
                assertTrue(network(provider,action)==(hg?(action==Action.PULL||action==Action.PUSH):(action==Action.COMMIT||action==Action.UPDATE||action==Action.SWITCH||action==Action.BRANCH)));
            }
        }
    }

}
