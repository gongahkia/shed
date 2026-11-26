package shed;
import java.nio.file.*;import java.awt.*;import java.awt.image.*;import java.util.*;import java.util.concurrent.*;import javax.swing.*;import javax.imageio.*;
import com.jediterm.terminal.*;import com.jediterm.terminal.model.*;import com.jediterm.terminal.emulator.*;
public final class TerminalColorPaintProbe {
 public static void main(String[] args)throws Exception {
  Path output=Path.of(args[0]).toRealPath();Path root=Path.of(System.getenv("SHED_PORTABLE_FIXTURE_ROOT")).toRealPath();if(!output.startsWith(root.resolve("run")))throw new IllegalArgumentException("Owned fixture required");
  var rows=new ArrayList<Map<String,Object>>();
  for(String theme:new String[]{"shed-light","shed-dark"}){
   FutureTask<Void> task=new FutureTask<>(()->{
    ConfigManager config=new ConfigManager();config.setTheme(theme);var settings=new PtyTerminalPane.ShedTerminalSettingsProvider(config,new Font("Monospaced",Font.PLAIN,18));var state=new StyleState();state.setDefaultStyle(settings.getDefaultStyle());var buffer=new TerminalTextBuffer(64,12,state);var panel=new AnimatedTerminalPanel(settings,buffer,state);panel.init(new JScrollBar());panel.setSize(panel.getPreferredSize());panel.putClientProperty("shed.caretAnimation",false);var terminal=new JediTerminal(panel,buffer,state);
    String text="Shed terminal: "+theme+" (in-memory emulator, no shell)\r\n\r\n\033[31mRED error output\033[0m\r\n\033[33mYELLOW warning output\033[0m\r\n\033[35mMAGENTA output\033[0m\r\n\033[32mGREEN success output\033[0m\r\n\033[34mBLUE output\033[0m\r\n\r\n\033[38;5;196m256-color red\033[0m  \033[38;2;18;164;239mRGB #12A4EF\033[0m\r\nPlain output remains unchanged.";
    var emulator=new JediEmulator(new ArrayTerminalDataStream(text.toCharArray()),terminal);while(emulator.hasNext())emulator.next();var image=new BufferedImage(panel.getWidth(),panel.getHeight(),BufferedImage.TYPE_INT_RGB);var g=image.createGraphics();try{panel.paint(g);}finally{g.dispose();}ImageIO.write(image,"png",output.resolve(theme+".png").toFile());var colors=new LinkedHashMap<String,Object>();for(int index:new int[]{1,3,5}){var color=settings.getTerminalColorPalette().getForeground(TerminalColor.index(index));colors.put(Integer.toString(index),String.format("#%02X%02X%02X",color.getRed(),color.getGreen(),color.getBlue()));}rows.add(Map.of("theme",theme,"colors",colors,"screen",buffer.getScreenLines()));return null;});SwingUtilities.invokeAndWait(task);task.get();
  }
  Files.writeString(output.resolve("result.json"),MiniJson.stringify(Map.of("state","PASS","rows",rows,"font","Monospaced 18","native_shell","NOT_USED")));System.exit(0);
 }
}