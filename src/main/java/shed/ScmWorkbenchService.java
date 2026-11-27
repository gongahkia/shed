package shed;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import static shed.ScmWorkbenchModel.*;

/** Fixed direct-argv operations, bounded by the existing process/job lifecycle. */
final class ScmWorkbenchService {
    @FunctionalInterface interface Runner { CommandResult run(Path root,List<String> command,AsyncJobService.JobToken token)throws Exception; }
    private final Runner runner;
    ScmWorkbenchService(){this((root,command,token)->BackgroundTaskProcess.start(command,root.toFile(),Map.of("HGPLAIN","1","HGPLAINEXCEPT","","HGENCODING","UTF-8","HGENCODINGMODE","strict"),MAX_OUTPUT,"").awaitCompletion(token,60_000));}
    ScmWorkbenchService(Runner runner){this.runner=runner;}
    private String run(Path root,List<String> command,AsyncJobService.JobToken token)throws Exception{
        if(token!=null&&token.isCancelled())throw new java.util.concurrent.CancellationException();
        if(!Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS))throw new IOException("Working copy no longer exists");
        CommandResult result;
        try{result=runner.run(root,List.copyOf(command),token);}catch(IOException unavailable){throw new IOException(command.getFirst()+" client could not run. Install/configure it on PATH, then Refresh. "+cleanError(unavailable.getMessage()),unavailable); }
        if(token!=null&&token.isCancelled())throw new java.util.concurrent.CancellationException();
        if(result.exitCode!=0)throw new IOException(cleanError(result.stderr.isBlank()?result.stdout:result.stderr));
        if(result.stdout.length()>MAX_OUTPUT||result.stdout.endsWith("\n[shed: output truncated]"))throw new IOException("Output exceeded the safe limit; refine this repository outside Shed before retrying");
        return result.stdout;
    }
    Snapshot status(Provider provider,Path root,AsyncJobService.JobToken token)throws Exception{
        List<Change> changes;String location;
        String output=run(root,ScmWorkbenchModel.status(provider),token);
        if(provider==Provider.MERCURIAL){
            changes=hgStatus(output);changes=hgConflicts(changes,run(root,command(provider,"resolve","--list","--template","{mergestatus} {path}\\0"),token));
            location=run(root,command(provider,"branch"),token).strip();
        }else {changes=svnStatus(output);location=svnLocation(run(root,command(provider,"info","--xml"),token));}
        return new Snapshot(provider,root,List.copyOf(changes),location);
    }
    String diff(Provider p,Path root,String file,AsyncJobService.JobToken token)throws Exception{target(root,file,false);return run(root,ScmWorkbenchModel.diff(p,file),token);}
    List<Revision> history(Provider p,Path root,AsyncJobService.JobToken token)throws Exception{String output=run(root,ScmWorkbenchModel.history(p),token);return p==Provider.MERCURIAL?hgHistory(output):svnHistory(output);}
    String revision(Provider p,Path root,String id,AsyncJobService.JobToken token)throws Exception{return run(root,revisionDiff(p,id),token);}
    List<Branch> branches(Path root,AsyncJobService.JobToken token)throws Exception{return hgBranches(run(root,command(Provider.MERCURIAL,"branches","--template","{node}\\0{branch}\\0"),token));}
    String mutate(Provider p,Path root,Action action,String file,String argument,AsyncJobService.JobToken token)throws Exception{
        if(Set.of(Action.ADD,Action.FORGET,Action.REVERT,Action.REMOVE,Action.RENAME,Action.RESOLVE).contains(action)){
            if(".".equals(file)&&(p==Provider.MERCURIAL||action!=Action.REVERT&&action!=Action.RESOLVE))throw new IOException("This operation requires a selected file, not the repository root");
            target(root,file,false);
        }
        if(action==Action.RENAME){Path destination=target(root,argument,false);if(Files.exists(destination,LinkOption.NOFOLLOW_LINKS))throw new IOException("Rename destination already exists");}
        String output=run(root,mutation(p,action,file,argument),token);return output.isBlank()?actionLabel(action)+" completed":output;
    }
    static String cleanError(String text){String value=text==null||text.isBlank()?"Client unavailable or operation failed":text;value=value.replaceAll("://[^/@\\s]+@","://[redacted]@");return value.substring(0,Math.min(1500,value.length()));}
    static String actionLabel(Action a){return switch(a){case ADD->"Add";case FORGET->"Forget";case REVERT->"Revert";case REMOVE->"Remove";case RENAME->"Rename";case RESOLVE->"Mark resolved";case COMMIT->"Commit";case UPDATE->"Update";case BRANCH->"New branch";case SWITCH->"Switch URL";case MERGE->"Merge branch";case SHELVE->"Shelve";case UNSHELVE->"Unshelve";case PULL->"Pull";case PUSH->"Push";};}
}
