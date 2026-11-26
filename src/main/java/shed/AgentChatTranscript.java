package shed;

import java.awt.*;
import java.util.ArrayList;
import javax.swing.*;
import javax.swing.text.*;
import org.commonmark.node.*;
import org.commonmark.parser.Parser;

/** Bounded selectable messages; Markdown becomes styled text, never executable HTML. */
final class AgentChatTranscript extends JTextPane {
    private record Message(String role,String text,boolean user,int rendered){}
    private static final int LIMIT=98304;
    private final ArrayList<Message> messages=new ArrayList<>();
    private final Parser markdown=Parser.builder().build();
    private String liveRole,live="";
    private int liveStart,liveMessageStart,retained;
    private Font body=new Font(Font.DIALOG,Font.PLAIN,13);
    private String codeFamily=Font.MONOSPACED;

    AgentChatTranscript(){
        setEditable(false);setName("agent.chat.transcript");setBorder(BorderFactory.createEmptyBorder(8,4,12,4));
        setEditorKit(new StyledEditorKit(){
            private final ViewFactory standard=super.getViewFactory();
            @Override public ViewFactory getViewFactory(){return element->{
                if(AbstractDocument.ParagraphElementName.equals(element.getName()))return new ParagraphView(element){
                    @Override public void paint(Graphics g,Shape allocation){if(Boolean.TRUE.equals(getAttributes().getAttribute("chat.user"))){var box=allocation.getBounds();g.setColor(color("Button.background",getBackground()));g.fillRoundRect(box.x,box.y,box.width,box.height,8,8);}super.paint(g,allocation);}
                };return standard.create(element);
            };}
        });
    }
    void user(String text){int start=getDocument().getLength();message("You",text,true);messages.add(new Message("You",text,true,getDocument().getLength()-start));retained+=text.length();trim();}
    void beginReply(String role){liveRole=role;live="";liveMessageStart=getDocument().getLength();header(role,false);liveStart=getDocument().getLength();}
    void updateReply(String text){
        if(liveRole==null||live.equals(text))return;
        try{if(text.startsWith(live))insert(text.substring(live.length()),attributes(false,false));else{getDocument().remove(liveStart,getDocument().getLength()-liveStart);insert(text,attributes(false,false));}live=text;}
        catch(BadLocationException failure){throw new IllegalStateException(failure);}
    }
    void finishReply(){if(liveRole==null)return;try{getDocument().remove(liveMessageStart,getDocument().getLength()-liveMessageStart);}catch(BadLocationException failure){throw new IllegalStateException(failure);}if(!live.isEmpty()){int start=getDocument().getLength();message(liveRole,live,false);messages.add(new Message(liveRole,live,false,getDocument().getLength()-start));retained+=live.length();}liveRole=null;live="";trim();}
    void clearMessages(){messages.clear();retained=0;liveRole=null;live="";setText("");}
    void appearance(Font font){body=font;setFont(font);rebuild();}
    void appearance(Font font,Font code){codeFamily=code.getFamily();appearance(font);}
    private void trim(){while((retained>LIMIT||messages.size()>128)&&!messages.isEmpty()){var first=messages.removeFirst();retained-=first.text().length();try{getDocument().remove(0,first.rendered());}catch(BadLocationException failure){throw new IllegalStateException(failure);}}}
    private static Color color(String key,Color fallback){var value=UIManager.getColor(key);return value==null?fallback:value;}
    private SimpleAttributeSet attributes(boolean bold,boolean code){
        var a=new SimpleAttributeSet();StyleConstants.setFontFamily(a,code?codeFamily:body.getFamily());StyleConstants.setFontSize(a,body.getSize());StyleConstants.setBold(a,bold);StyleConstants.setForeground(a,getForeground());
        if(code)StyleConstants.setBackground(a,color("Button.background",getBackground()));return a;
    }
    private void insert(String text,AttributeSet style){try{getStyledDocument().insertString(getDocument().getLength(),text,style);}catch(BadLocationException failure){throw new IllegalStateException(failure);}}
    private void header(String role,boolean user){int start=getDocument().getLength();var style=attributes(true,false);StyleConstants.setForeground(style,color("Label.disabledForeground",getForeground()));insert(role+"\n",style);block(start,false,8,3);}
    private void block(int start,boolean user,float before,float after){var p=new SimpleAttributeSet();p.addAttribute("chat.user",user);StyleConstants.setSpaceAbove(p,before);StyleConstants.setSpaceBelow(p,after);StyleConstants.setLeftIndent(p,user?10:0);StyleConstants.setRightIndent(p,user?10:0);StyleConstants.setLineSpacing(p,.12f);getStyledDocument().setParagraphAttributes(start,getDocument().getLength()-start,p,false);}
    private void rebuild(){
        String role=liveRole,current=live;int dot=getCaretPosition(),mark=getCaret().getMark();setText("");
        for(int i=0;i<messages.size();i++){var m=messages.get(i);int start=getDocument().getLength();message(m.role(),m.text(),m.user());messages.set(i,new Message(m.role(),m.text(),m.user(),getDocument().getLength()-start));}
        if(role!=null){liveMessageStart=getDocument().getLength();header(role,false);liveStart=getDocument().getLength();insert(current,attributes(false,false));}
        int length=getDocument().getLength();getCaret().setDot(Math.min(mark,length));getCaret().moveDot(Math.min(dot,length));
    }
    private void message(String role,String text,boolean user){header(role,user);int start=getDocument().getLength();if(user){insert(text+"\n",attributes(false,false));block(start,true,6,16);}else{formatted(text);insert("\n",attributes(false,false));block(start,false,0,12);}}
    private void formatted(String text){
        markdown.parse(text).accept(new AbstractVisitor(){
            int bold,italic,depth;
            @Override protected void visitChildren(Node parent){if(depth>=32){insert("[Nested content omitted]",attributes(false,false));return;}depth++;try{super.visitChildren(parent);}finally{depth--;}}
            private SimpleAttributeSet style(boolean code){var a=attributes(bold>0,code);StyleConstants.setItalic(a,italic>0);return a;}
            @Override public void visit(Text n){insert(n.getLiteral(),style(false));}
            @Override public void visit(SoftLineBreak n){insert(" ",style(false));}
            @Override public void visit(HardLineBreak n){insert("\n",style(false));}
            @Override public void visit(Paragraph n){visitChildren(n);insert("\n",style(false));}
            @Override public void visit(StrongEmphasis n){bold++;visitChildren(n);bold--;}
            @Override public void visit(Emphasis n){italic++;visitChildren(n);italic--;}
            @Override public void visit(Code n){insert(n.getLiteral(),style(true));}
            @Override public void visit(Heading n){bold++;visitChildren(n);bold--;insert("\n\n",style(false));}
            @Override public void visit(FencedCodeBlock n){if(!n.getInfo().isBlank())insert(n.getInfo()+"\n",attributes(true,false));insert(n.getLiteral()+"\n",style(true));}
            @Override public void visit(IndentedCodeBlock n){insert(n.getLiteral()+"\n",style(true));}
            @Override public void visit(ListItem n){insert("• ",style(false));visitChildren(n);}
            @Override public void visit(Link n){visitChildren(n);insert(" ("+n.getDestination()+")",style(false));}
            @Override public void visit(org.commonmark.node.Image n){insert("[Image: ",style(false));visitChildren(n);insert("]",style(false));}
            @Override public void visit(HtmlInline n){insert(n.getLiteral(),style(true));}
            @Override public void visit(HtmlBlock n){insert(n.getLiteral()+"\n",style(true));}
        });
    }
}
