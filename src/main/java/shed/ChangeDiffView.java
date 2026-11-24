package shed;
import java.awt.*;
import javax.swing.*;
import javax.swing.text.*;

/** Bounded, themed unified diff shared by watched changes and existing Git patches. */
final class ChangeDiffView extends JTextPane {
    ChangeDiffView(){setEditable(false);setName("agent.liveDiff");AccessibilitySupport.describe(this,"Live file diff","Added and removed source lines. Select a file, then navigate between changes.");}
    void showChange(ExternalChangeSnapshot.Change change,Font font){
        StringBuilder patch=new StringBuilder();
        patch.append(change.previous()==null?change.path():change.previous()+" → "+change.path()).append('\n');
        if(change.hunks().isEmpty())patch.append(change.before().equals(change.after())?"Contents unchanged\n":change.after().isEmpty()?change.before():change.after());
        for(var h:change.hunks()){
            int line=1+(int)change.before().substring(0,h.start()).chars().filter(c->c=='\n').count();
            patch.append("@@ line ").append(line).append(" @@\n");
            String prefix=change.before().substring(0,h.start());var context=prefix.split("\n",-1);
            for(int i=Math.max(0,context.length-4);i<context.length-1;i++)patch.append(' ').append(context[i]).append('\n');
            var display=change.display(h);
            append(patch,'-',display.before());append(patch,'+',display.after());
            String suffix=change.before().substring(display.end());int end=0;
            for(int i=0;i<3&&end<suffix.length();i++){int n=suffix.indexOf('\n',end);int stop=n<0?suffix.length():n+1;patch.append(' ').append(suffix,end,stop);end=stop;}
            if(patch.length()>100000)break;
        }
        showPatch(patch.toString(),font);
    }
    void refreshAppearance(Font font){
        setFont(font);Color background=UIManager.getColor("TextArea.background"),foreground=UIManager.getColor("TextArea.foreground");
        if(background!=null)setBackground(background);if(foreground!=null)setForeground(foreground);
        var document=getStyledDocument();var base=new SimpleAttributeSet();StyleConstants.setForeground(base,getForeground());StyleConstants.setBackground(base,getBackground());
        document.setCharacterAttributes(0,document.getLength(),base,false);
        int offset=0;for(String line:getText().split("\n",-1)){
            Color color=line.startsWith("+")?UIManager.getColor("shed.success"):line.startsWith("-")?UIManager.getColor("shed.error"):null;
            if(color!=null){var attributes=new SimpleAttributeSet();StyleConstants.setForeground(attributes,AccessibilitySupport.readableText(color,getBackground()));StyleConstants.setBackground(attributes,new Color((color.getRed()+getBackground().getRed()*7)/8,(color.getGreen()+getBackground().getGreen()*7)/8,(color.getBlue()+getBackground().getBlue()*7)/8));document.setCharacterAttributes(offset,Math.min(line.length()+1,document.getLength()-offset),attributes,false);}
            offset+=line.length()+1;if(offset>=document.getLength())break;
        }
    }
    static int nextHunk(String text,int caret,int direction){
        int index=direction>0?text.indexOf("\n@@",Math.max(0,caret)):text.lastIndexOf("\n@@",Math.max(-1,caret-2));
        return index<0?-1:index+1;
    }
    private static void append(StringBuilder out,char sign,String text){if(text.isEmpty())return;String[] lines=text.split("\n",-1);int count=lines.length-(text.endsWith("\n")?1:0);for(int i=0;i<count;i++){out.append(sign).append(lines[i]).append('\n');}}
    void showPatch(String text,Font font){
        setFont(font);setText("");var document=getStyledDocument();Color background=UIManager.getColor("TextArea.background"),foreground=UIManager.getColor("TextArea.foreground");
        if(background!=null)setBackground(background);if(foreground!=null)setForeground(foreground);
        text=text.substring(0,Math.min(100000,text.length()));
        String previous="";int previousOffset=0;
        for(String line:text.split("\n",-1)){
            var attributes=new SimpleAttributeSet();char first=line.isEmpty()?' ':line.charAt(0);
            Color color=first=='+'?UIManager.getColor("shed.success"):first=='-'?UIManager.getColor("shed.error"):null;
            if(color!=null){StyleConstants.setForeground(attributes,AccessibilitySupport.readableText(color,getBackground()));StyleConstants.setBackground(attributes,new Color((color.getRed()+getBackground().getRed()*7)/8,(color.getGreen()+getBackground().getGreen()*7)/8,(color.getBlue()+getBackground().getBlue()*7)/8));}
            if(line.startsWith("@@"))StyleConstants.setBold(attributes,true);
            try{int offset=document.getLength();document.insertString(offset,line+"\n",attributes);
                if(line.startsWith("+")&&previous.startsWith("-")&&!line.startsWith("+++")&&!previous.startsWith("---")){
                    String a=previous.substring(1),b=line.substring(1);int prefix=0,suffix=0;
                    while(prefix<Math.min(a.length(),b.length())&&a.charAt(prefix)==b.charAt(prefix))prefix++;
                    while(suffix<Math.min(a.length(),b.length())-prefix&&a.charAt(a.length()-1-suffix)==b.charAt(b.length()-1-suffix))suffix++;
                    var word=new SimpleAttributeSet();StyleConstants.setBold(word,true);StyleConstants.setUnderline(word,true);
                    document.setCharacterAttributes(previousOffset+1+prefix,Math.max(0,a.length()-prefix-suffix),word,false);
                    document.setCharacterAttributes(offset+1+prefix,Math.max(0,b.length()-prefix-suffix),word,false);
                }previous=line;previousOffset=offset;
            }catch(BadLocationException impossible){throw new IllegalStateException(impossible);}
        }setCaretPosition(0);
    }
}
