package quickcomplete;

import arc.Core;
import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Fill;
import arc.graphics.g2d.Font;
import arc.graphics.g2d.GlyphLayout;
import arc.math.geom.Vec2;
import arc.scene.Element;
import arc.scene.ui.TextField;
import arc.scene.ui.layout.Scl;
import arc.util.Align;
import arc.util.Tmp;
import mindustry.graphics.Pal;
import mindustry.ui.Fonts;

import java.util.ArrayList;
import java.util.List;

import quickcomplete.ChatCompleter.Mode;

/** 候选列表 + 低透明度 ghost 预览的绘制组件，挂在 scene.root 上。 */
public class CompleterOverlay extends Element{

    public static class RenderRow{
        public final String label;    // 主文本（原始候选 或 颜色中文名/hex）
        public final String preview;  // 可选：以 color 渲染的预览文本
        public final Color color;     // 可选：预览颜色

        public RenderRow(String label, String preview, Color color){
            this.label = label;
            this.preview = preview;
            this.color = color;
        }
    }

    private final TextField chatfield;
    private final Font font = Fonts.def;
    private final GlyphLayout layout = new GlyphLayout(true);
    private final List<RenderRow> rows = new ArrayList<>();

    private Mode mode = Mode.STANDARD;
    private List<Candidate> candidates = new ArrayList<>();
    private List<ColorTags.ColorEntry> colors = new ArrayList<>();
    private int focus = -1;
    private String input = "";
    private String prefix = "";
    private String content = "";

    public CompleterOverlay(TextField chatfield){
        this.chatfield = chatfield;
        visible = false;
    }

    public void attach(){
        Core.scene.root.addChild(this);
    }

    public void setFocus(int f){
        this.focus = f;
    }

    public void refresh(Mode mode, List<Candidate> candidates, List<ColorTags.ColorEntry> colors,
                        int focus, String input, String prefix, String content){
        this.mode = mode;
        this.candidates = candidates;
        this.colors = colors;
        this.focus = focus;
        this.input = input;
        this.prefix = prefix;
        this.content = content;

        rows.clear();
        if(mode == Mode.PREFIX){
            for(ColorTags.ColorEntry c : colors) rows.add(rowForColor(c, content));
        }else{
            for(Candidate c : candidates) rows.add(new RenderRow(c.text, null, null));
        }

        visible = showGhost() || showList();
    }

    public void hide(){
        visible = false;
        rows.clear();
    }

    private RenderRow rowForColor(ColorTags.ColorEntry c, String content){
        String label = c.display();
        String preview = content.isEmpty() ? "预览文字" : content;
        return new RenderRow(label, preview, c.color());
    }

    private boolean showGhost(){
        return (mode == Mode.STANDARD || mode == Mode.SUFFIX) && candidates.size() == 1
            && candidates.get(0).matchType == Candidate.MatchType.PREFIX;
    }

    private boolean showList(){
        if(mode == Mode.PREFIX) return !rows.isEmpty();
        if(candidates.size() >= 2) return true;
        return candidates.size() == 1 && candidates.get(0).matchType != Candidate.MatchType.PREFIX;
    }

    @Override
    public void draw(){
        if(!visible || chatfield == null) return;
        Vec2 p = chatfield.localToStageCoordinates(Tmp.v1.set(0, 0));
        float cx = p.x, cy = p.y;

        if(showGhost()){
            drawGhost(cx, cy);
        }else if(showList()){
            drawList(cx, cy);
        }
    }

    private void drawGhost(float cx, float cy){
        String cand = candidates.get(0).text;
        if(prefix.length() >= cand.length()) return;
        String remainder = cand.substring(prefix.length());
        if(remainder.isEmpty()) return;

        float x = cx + font.getData().cursorX + measure(input);
        float y = cy + chatfield.getHeight() / 2f + font.getCapHeight() / 2f;

        drawText(remainder, x, y, new Color(1f, 1f, 1f, 0.35f));
    }

    private void drawList(float cx, float cy){
        float rowH = font.getLineHeight() + Scl.scl(6);
        float pad = Scl.scl(6);
        float listW = measureListWidth() + pad * 2f;
        float listH = rows.size() * rowH + pad * 2f;
        float listX = cx;
        float listY = cy + chatfield.getHeight() + Scl.scl(4);

        Draw.color(0f, 0f, 0f, 0.65f);
        Fill.crect(listX, listY, listW, listH);

        for(int i = 0; i < rows.size(); i++){
            float rowTop = listY + listH - pad - i * rowH;
            if(i == focus){
                Draw.color(Pal.accent.r, Pal.accent.g, Pal.accent.b, 0.45f);
                Fill.crect(listX + Scl.scl(2), rowTop - rowH + Scl.scl(2), listW - Scl.scl(4), rowH - Scl.scl(4));
            }
            RenderRow r = rows.get(i);
            float tx = listX + pad;
            float ty = rowTop - Scl.scl(2);

            drawText(r.label, tx, ty, i == focus ? Color.white : Color.lightGray);
            if(r.preview != null && r.color != null){
                float px = tx + measure(r.label) + Scl.scl(8);
                drawText(r.preview, px, ty, r.color);
            }
        }
        Draw.color();
    }

    /** 以关闭 markup 的方式绘制纯文本（避免 "[xxx]" 被当成颜色标签吞掉）。 */
    private void drawText(String text, float x, float y, Color color){
        boolean had = font.getData().markupEnabled;
        font.getData().markupEnabled = false;
        font.setColor(color);
        font.draw(text, x, y);
        font.getData().markupEnabled = had;
        font.setColor(Color.white);
    }

    private float measureListWidth(){
        float w = 0f;
        for(RenderRow r : rows){
            float rw = measure(r.label);
            if(r.preview != null){
                rw += Scl.scl(8) + measure(r.preview);
            }
            w = Math.max(w, rw);
        }
        return w;
    }

    private float measure(String s){
        layout.setText(font, s, Color.white, 0, Align.left, false);
        return layout.width;
    }
}
