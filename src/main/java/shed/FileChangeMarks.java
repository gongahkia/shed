package shed;

import java.nio.file.Path;
import java.util.*;

/** Immutable path metadata for explorer decoration, never editor documents. */
final class FileChangeMarks {
    record Mark(String code,String detail,int files,boolean removed){}
    static Map<Path,Mark> from(Path root,List<ExternalChangeSnapshot.Change> changes){
        var marks=new LinkedHashMap<Path,Mark>();var counts=new LinkedHashMap<Path,Integer>();var codes=new HashMap<Path,String>();
        for(var change:changes){
            Path path=root.resolve(change.path()).normalize();if(!path.startsWith(root))continue;
            String detail=change.kind().equals("R?")?"Possible move (text similarity)":switch(change.kind()){case "A"->"Added";case "D"->"Deleted";case "R"->"Renamed or moved";default->"Modified";};
            if(change.previous()!=null)detail+=" · "+change.previous()+" → "+change.path();
            marks.put(path,new Mark(change.kind(),detail,1,change.kind().equals("D")));
            var parents=new HashSet<Path>();ancestors(root,path,parents);
            if(change.previous()!=null){Path previous=root.resolve(change.previous()).normalize();if(previous.startsWith(root)){marks.put(previous,new Mark(change.kind(),"Moved to "+change.path(),1,true));ancestors(root,previous,parents);}}
            for(var parent:parents)if(counts.size()<4096||counts.containsKey(parent)){counts.merge(parent,1,Integer::sum);codes.merge(parent,change.kind(),(a,b)->a.equals(b)?a:"M");}
        }
        counts.forEach((path,count)->marks.putIfAbsent(path,new Mark(codes.get(path),count+" changed descendants",count,false)));
        return Map.copyOf(marks);
    }
    private static void ancestors(Path root,Path path,Set<Path> parents){
        for(Path parent=path.getParent();parent!=null&&parent.startsWith(root);parent=parent.getParent()){
            parents.add(parent);
        }
    }
    static Set<String> recent(Path root,Collection<String> paths){
        var result=new HashSet<String>();for(String name:paths){Path path=root.resolve(name).normalize();if(!path.startsWith(root))continue;
            for(Path current=path;current!=null&&current.startsWith(root)&&result.size()<4096;current=current.getParent())result.add(current.toString());
        }return Set.copyOf(result);
    }
}
