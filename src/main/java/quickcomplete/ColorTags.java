package quickcomplete;

import arc.graphics.Color;
import arc.graphics.Colors;

import java.util.ArrayList;
import java.util.List;

/** 游戏内颜色标签静态表（前缀补全用），不学习、不自动修改。含命名色与常用深浅预设。 */
public class ColorTags{

    public static class ColorEntry{
        public final String tag;      // 英文标签（"red"）或十六进制（"#ff0000"）
        public final String chinese;  // 中文名（"红色"/"浅黄"），自定义 hex 为空
        public final String family;   // 色系（"红色系"）

        public ColorEntry(String tag, String chinese, String family){
            this.tag = tag;
            this.chinese = chinese;
            this.family = family;
        }

        public Color color(){
            if(tag.startsWith("#")){
                try{
                    return Color.valueOf(tag);
                }catch(Throwable e){
                    return Color.white;
                }
            }
            Color c = Colors.get(tag);
            return c == null ? Color.white : c;
        }

        /** 列表里显示的标签名：中文名优先，自定义 hex 显示 hex 串。 */
        public String display(){
            return chinese == null || chinese.isEmpty() ? tag : chinese;
        }

        /** 插入到输入框的字面标签，如 "red" 或 "#ff0000"。 */
        public String insertTag(){
            return tag;
        }
    }

    public static final List<ColorEntry> ALL = new ArrayList<>();

    static{
        // 命名色
        add("red", "红色", "红色系");
        add("scarlet", "猩红", "红色系");
        add("crimson", "深红", "红色系");
        add("coral", "珊瑚色", "红色系");
        add("salmon", "鲑鱼色", "红色系");
        add("pink", "粉色", "红色系");
        add("magenta", "洋红", "红色系");
        add("maroon", "栗色", "红色系");
        add("brick", "砖红", "红色系");

        add("orange", "橙色", "橙色系");
        add("gold", "金色", "橙色系");
        add("goldenrod", "金菊色", "橙色系");
        add("yellow", "黄色", "橙色系");
        add("brown", "棕色", "橙色系");
        add("tan", "棕褐色", "橙色系");

        add("green", "绿色", "绿色系");
        add("acid", "酸绿色", "绿色系");
        add("lime", "青柠色", "绿色系");
        add("forest", "森林绿", "绿色系");
        add("olive", "橄榄色", "绿色系");

        add("cyan", "青色", "蓝色系");
        add("teal", "蓝绿色", "蓝色系");
        add("sky", "天蓝色", "蓝色系");
        add("blue", "蓝色", "蓝色系");
        add("royal", "宝蓝色", "蓝色系");
        add("navy", "藏青色", "蓝色系");
        add("slate", "蓝灰色", "蓝色系");

        add("purple", "紫色", "紫色系");
        add("violet", "紫罗兰色", "紫色系");

        add("white", "白色", "无色系");
        add("black", "黑色", "无色系");
        add("gray", "灰色", "无色系");
        add("lightgray", "浅灰色", "无色系");
        add("darkgray", "深灰色", "无色系");

        add("accent", "强调色", "其他");
        add("stat", "统计色", "其他");
        add("negstat", "负面统计色", "其他");
        add("unlaunched", "未发射色", "其他");
        add("highlight", "高亮色", "其他");

        // 常用深浅预设（十六进制）
        add("#ff9999", "浅红", "红色系");
        add("#8b0000", "深红", "红色系");
        add("#ffb6c1", "浅粉", "红色系");
        add("#ff69b4", "深粉", "红色系");
        add("#ffcc99", "浅橙", "橙色系");
        add("#cc5500", "深橙", "橙色系");
        add("#ffff99", "浅黄", "橙色系");
        add("#b8860b", "深黄", "橙色系");
        add("#99ff99", "浅绿", "绿色系");
        add("#006400", "深绿", "绿色系");
        add("#99ffff", "浅青", "蓝色系");
        add("#008b8b", "深青", "蓝色系");
        add("#99ccff", "浅蓝", "蓝色系");
        add("#00008b", "深蓝", "蓝色系");
        add("#cc99ff", "浅紫", "紫色系");
        add("#4b0082", "深紫", "紫色系");
        add("#cccccc", "浅灰", "无色系");
        add("#555555", "深灰", "无色系");
    }

    private static void add(String tag, String chinese, String family){
        ALL.add(new ColorEntry(tag, chinese, family));
    }

    /** 中/英文部分匹配搜索：空输入返回全部；否则按相关度排序。 */
    public static List<ColorEntry> search(String input){
        List<ColorEntry> out = new ArrayList<>();
        String q = input == null ? "" : input.trim();
        if(q.isEmpty()){
            out.addAll(ALL);
            return out;
        }
        String ql = q.toLowerCase();
        for(ColorEntry e : ALL){
            if(matches(e, q, ql)) out.add(e);
        }
        out.sort((a, b) -> Integer.compare(score(a, q, ql), score(b, q, ql)));
        return out;
    }

    /** 若输入为合法的 #RRGGBB 或 #RRGGBBAA，返回一个十六进制颜色候选，否则 null。 */
    public static ColorEntry parseHex(String input){
        String s = input == null ? "" : input.trim();
        if(s.length() < 2 || s.charAt(0) != '#') return null;
        String hex = s.substring(1);
        if(hex.length() != 6 && hex.length() != 8) return null;
        for(int i = 0; i < hex.length(); i++){
            char c = hex.charAt(i);
            boolean ok = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if(!ok) return null;
        }
        return new ColorEntry(s, "", "");
    }

    /** 去除字符串中的颜色标签（如 [pink]、[#ff0000]、[]）。 */
    public static String stripColors(String s){
        if(s == null || s.indexOf('[') < 0) return s;
        StringBuilder sb = new StringBuilder(s.length());
        int i = 0;
        while(i < s.length()){
            char c = s.charAt(i);
            if(c == '['){
                int close = s.indexOf(']', i + 1);
                if(close > i && isColorTag(s.substring(i + 1, close))){
                    i = close + 1;
                    continue;
                }
            }
            sb.append(c);
            i++;
        }
        return sb.toString();
    }

    private static boolean isColorTag(String inner){
        if(inner.isEmpty()) return true; // "[]" 结束颜色
        if(inner.charAt(0) == '#'){
            String hex = inner.substring(1);
            if(hex.length() != 6 && hex.length() != 8) return false;
            for(int i = 0; i < hex.length(); i++){
                char c = hex.charAt(i);
                boolean ok = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
                if(!ok) return false;
            }
            return true;
        }
        return Colors.get(inner) != null || Colors.get(inner.toLowerCase()) != null;
    }

    private static boolean matches(ColorEntry e, String q, String ql){
        if(e.tag.startsWith(ql)) return true;
        if(e.chinese.contains(q)) return true;
        if(e.family.contains(q)) return true;
        if(q.contains(e.chinese)) return true;
        if(q.contains(e.tag)) return true;
        return false;
    }

    private static int score(ColorEntry e, String q, String ql){
        if(e.chinese.equals(q)) return 0;
        if(e.tag.equals(ql)) return 1;
        if(e.tag.startsWith(ql)) return 2;
        if(e.chinese.startsWith(q)) return 3;
        if(e.family.contains(q)) return 4;
        if(q.contains(e.chinese)) return 5;
        return 6;
    }
}
