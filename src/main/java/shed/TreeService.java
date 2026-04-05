package shed;

import java.io.File;

public class TreeService {
    public File resolveRoot(String pathArgument) {
        String target = pathArgument == null ? "" : pathArgument.trim();
        return target.isEmpty() ? new File(".") : new File(target);
    }

    public String titleSuffix(File root) {
        String name = root.getName();
        if (name == null || name.isEmpty()) {
            return root.getAbsolutePath();
        }
        return name;
    }
}
