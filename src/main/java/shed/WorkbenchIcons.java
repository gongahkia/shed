package shed;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import javax.swing.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.apache.batik.parser.AWTPathProducer;
import org.apache.batik.parser.PathParser;

/** Bundled Microsoft Codicons, painted as vectors at the current UI scale. */
enum WorkbenchIcons implements Icon {
    CLOSE("close"), DETACH("link-external"), SEARCH("search"), COMMANDS("terminal"),
    SPLIT("split-horizontal"), TOOLS("layout-panel"), SETTINGS("settings-gear"), PLAY("play"),
    STOP("debug-stop"), REFRESH("refresh"), PREVIOUS("chevron-left"), NEXT("chevron-right"),
    ADD("add"), SEND("send"), COPY("copy"), FOLDER("folder"), FOLDER_OPEN("folder-opened"),
    FILE("file"), CODE("file-code"), IMAGE("file-media"), MARKDOWN("markdown"), JSON("json"),
    CHEVRON_DOWN("chevron-down");

    private record Vector(double width,double height,List<Shape> paths) { }
    private final String resource;
    private volatile Vector vector;
    WorkbenchIcons(String resource){this.resource=resource;}
    public int getIconWidth(){return WorkbenchStyle.px(16);}
    public int getIconHeight(){return WorkbenchStyle.px(16);}
    private Vector vector(){
        var result=vector;if(result!=null)return result;
        try(var input=WorkbenchIcons.class.getResourceAsStream("/assets/icons/codicons/"+resource+".svg")){
            if(input==null)throw new IllegalStateException("Missing bundled Codicon: "+resource);
            var factory=DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,"");factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");
            factory.setXIncludeAware(false);factory.setExpandEntityReferences(false);
            var root=factory.newDocumentBuilder().parse(input).getDocumentElement();
            var box=root.getAttribute("viewBox").trim().split("\\s+");var paths=new ArrayList<Shape>();
            var elements=root.getElementsByTagName("path");
            for(int i=0;i<elements.getLength();i++){
                var path=(org.w3c.dom.Element)elements.item(i);var producer=new AWTPathProducer();
                producer.setWindingRule("evenodd".equals(path.getAttribute("fill-rule"))?java.awt.geom.Path2D.WIND_EVEN_ODD:java.awt.geom.Path2D.WIND_NON_ZERO);
                var parser=new PathParser();parser.setPathHandler(producer);parser.parse(path.getAttribute("d"));paths.add(producer.getShape());
            }
            if(box.length!=4||paths.isEmpty())throw new IllegalStateException("Invalid bundled Codicon: "+resource);
            result=new Vector(Double.parseDouble(box[2]),Double.parseDouble(box[3]),List.copyOf(paths));vector=result;return result;
        }catch(Exception failure){throw new IllegalStateException("Could not load bundled Codicon: "+resource,failure);}
    }
    public void paintIcon(Component component,Graphics graphics,int x,int y){
        var data=vector();var g=(Graphics2D)graphics.create();
        try{
            g.clipRect(x,y,getIconWidth(),getIconHeight());
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
            g.translate(x,y);g.scale(getIconWidth()/data.width(),getIconHeight()/data.height());
            Color color=component!=null&&!component.isEnabled()?UIManager.getColor("shed.muted"):component==null?UIManager.getColor("Label.foreground"):component.getForeground();
            g.setColor(color==null?Color.GRAY:color);for(var path:data.paths())g.fill(path);
        }finally{g.dispose();}
    }
    static Icon file(String name){
        String value=name.toLowerCase(java.util.Locale.ROOT);
        if(value.endsWith(".md")||value.endsWith(".markdown"))return MARKDOWN;
        if(value.endsWith(".json")||value.endsWith(".jsonc"))return JSON;
        if(value.matches(".*\\.(png|jpe?g|gif|svg|webp|ico)$"))return IMAGE;
        if(value.matches(".*\\.(java|kt|go|rs|py|js|jsx|ts|tsx|c|cpp|h|sh|zsh|xml|html|css|toml|ya?ml)$")||value.equals("makefile"))return CODE;
        return FILE;
    }
}
