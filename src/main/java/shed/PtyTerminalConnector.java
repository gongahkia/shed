package shed;

import com.jediterm.core.util.TermSize;
import com.jediterm.terminal.ProcessTtyConnector;
import com.pty4j.PtyProcess;
import com.pty4j.WinSize;
import java.nio.charset.Charset;
import java.util.List;

final class PtyTerminalConnector extends ProcessTtyConnector {
    private final PtyProcess process;
    private final String name;
    private TermSize lastSize;
    private final java.util.List<java.util.function.Consumer<String>> readers=new java.util.concurrent.CopyOnWriteArrayList<>();
    void addReader(java.util.function.Consumer<String> reader){readers.add(reader);}
    void removeReader(java.util.function.Consumer<String> reader){readers.remove(reader);}
    @Override public int read(char[] buffer,int offset,int length)throws java.io.IOException{
        int count=super.read(buffer,offset,length);
        if(count>0&&!readers.isEmpty()){String text=new String(buffer,offset,count);for(var reader:readers)reader.accept(text);}
        return count;
    }

    PtyTerminalConnector(PtyProcess process, List<String> commandLine, Charset charset) {
        super(process, charset, commandLine);
        this.process = process;
        this.name = String.join(" ", commandLine);
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public synchronized void resize(TermSize termSize) {
        if (termSize == null || termSize.getColumns() <= 0 || termSize.getRows() <= 0 || termSize.equals(lastSize) || !process.isRunning()) {
            return;
        }
        try {
            process.setWinSize(new WinSize(termSize.getColumns(), termSize.getRows()));
            lastSize = termSize;
        } catch (IllegalStateException ignored) {
        }
    }

    @Override
    public void close() {
        super.close();
        if (process.isRunning()) {
            process.destroy();
        }
    }
}
