package quickcomplete;

import arc.files.Fi;
import arc.struct.ObjectMap;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;

/** 词库：完整消息词库 + 后缀词库 + 颜色使用统计，跨会话持久化。 */
public class WordLibrary{

    /** 后缀候选进入列表所需的最小出现次数。 */
    public static final int SUFFIX_MIN_COUNT = 3;

    public static class Entry{
        public final String text;
        public int count;
        public long lastUsed;

        Entry(String text, int count, long lastUsed){
            this.text = text;
            this.count = count;
            this.lastUsed = lastUsed;
        }
    }

    /** 累计发送的消息总数（含带前置指令的消息，只按条数计）。 */
    public long totalSent = 0;

    /** 完整消息词库（有效文本 -> 记录）。 */
    public final ObjectMap<String, Entry> messages = new ObjectMap<>();
    /** 后缀词库（末尾片段 -> 记录）。 */
    public final ObjectMap<String, Entry> suffixes = new ObjectMap<>();
    /** 前缀颜色使用统计（颜色标签 -> 使用次数）。 */
    public final ObjectMap<String, Integer> colors = new ObjectMap<>();

    private static final Comparator<Candidate> DESC = (a, b) -> {
        if(a.count != b.count) return Integer.compare(b.count, a.count);
        if(a.matchType != b.matchType) return Integer.compare(a.matchType.ordinal(), b.matchType.ordinal());
        if(a.lastUsed != b.lastUsed) return Long.compare(b.lastUsed, a.lastUsed);
        int l = Integer.compare(a.text.length(), b.text.length());
        if(l != 0) return l;
        return a.text.compareTo(b.text);
    };

    /** 记录一条消息（已剔除前置指令的有效文本）。 */
    public void record(String message){
        String m = message == null ? "" : message.trim();
        if(m.isEmpty()) return;
        totalSent++;
        bump(messages, m);
        extractSuffixes(m);
    }

    /** 记录一次颜色标签的使用。 */
    public void recordColor(String tag){
        if(tag == null || tag.isEmpty()) return;
        colors.put(tag, colors.get(tag, 0) + 1);
    }

    /** 切换置顶：次数<2 -> 置为 2（永久）；已永久 -> 删除。返回 true=已删除，false=已保存。 */
    public boolean togglePin(String text){
        if(text == null || text.isEmpty()) return false;
        long now = System.currentTimeMillis();
        Entry e = messages.get(text);
        if(e == null || e.count < 2){
            if(e == null){
                messages.put(text, new Entry(text, 2, now));
            }else{
                e.count = 2;
                e.lastUsed = now;
            }
            return false;
        }else{
            messages.remove(text);
            return true;
        }
    }

    /** 删除后缀项。返回是否确实删除。 */
    public boolean deleteSuffix(String text){
        if(text == null || text.isEmpty()) return false;
        return suffixes.remove(text) != null;
    }

    private void bump(ObjectMap<String, Entry> map, String text){
        Entry e = map.get(text);
        long now = System.currentTimeMillis();
        if(e == null){
            map.put(text, new Entry(text, 1, now));
        }else{
            e.count++;
            e.lastUsed = now;
        }
    }

    private void extractSuffixes(String message){
        int end = message.length();
        while(end > 0 && isPunct(message.charAt(end - 1))) end--;
        if(end < message.length()){
            bump(suffixes, message.substring(end));
        }

        String core = message.substring(0, end);
        if(core.isEmpty()) return;

        int lastSpace = -1;
        boolean cjk = false;
        for(int i = 0; i < core.length(); i++){
            char c = core.charAt(i);
            if(Character.isWhitespace(c)) lastSpace = i;
            if(isCJK(c)) cjk = true;
        }

        if(lastSpace >= 0){
            bump(suffixes, core.substring(lastSpace + 1));
        }else if(cjk){
            int n = Math.min(4, core.length());
            for(int len = 1; len <= n; len++){
                bump(suffixes, core.substring(core.length() - len));
            }
        }else{
            bump(suffixes, core);
        }
    }

    private static boolean isPunct(char c){
        return !Character.isLetterOrDigit(c) && !Character.isWhitespace(c);
    }

    private static boolean isCJK(char c){
        return (c >= 0x4E00 && c <= 0x9FFF) || (c >= 0x3400 && c <= 0x4DBF);
    }

    /** 匹配查询完整消息词库：字面前缀 / 忽略颜色前缀 / 部分包含（>=minMatchLen 字）。结果按次数降序。 */
    public List<Candidate> queryMessages(String prefix, int minMatchLen){
        List<Candidate> out = new ArrayList<>();
        String p = prefix == null ? "" : prefix;
        if(p.isEmpty()) return out;
        for(Entry e : messages.values()){
            String stripped = ColorTags.stripColors(e.text);
            if(e.text.startsWith(p)){
                out.add(new Candidate(e.text, e.count, e.lastUsed, Candidate.MatchType.PREFIX));
            }else if(!stripped.equals(e.text) && stripped.startsWith(p)){
                out.add(new Candidate(e.text, e.count, e.lastUsed, Candidate.MatchType.COLOR));
            }else if(p.length() >= minMatchLen && stripped.contains(p)){
                out.add(new Candidate(e.text, e.count, e.lastUsed, Candidate.MatchType.PARTIAL));
            }
        }
        out.sort(DESC);
        return out;
    }

    /** 前缀匹配查询后缀词库（仅次数>=3），结果按次数降序。 */
    public List<Candidate> querySuffixes(String partial){
        List<Candidate> out = new ArrayList<>();
        String p = partial == null ? "" : partial;
        for(Entry e : suffixes.values()){
            if(e.count >= SUFFIX_MIN_COUNT && (p.isEmpty() || e.text.startsWith(p))){
                out.add(new Candidate(e.text, e.count, e.lastUsed));
            }
        }
        out.sort(DESC);
        return out;
    }

    /** 出现次数最高的前 n 条消息（降序）。 */
    public List<Candidate> topMessages(int n){
        List<Candidate> all = new ArrayList<>();
        for(Entry e : messages.values()){
            all.add(new Candidate(e.text, e.count, e.lastUsed));
        }
        all.sort(DESC);
        if(all.size() <= n) return all;
        return new ArrayList<>(all.subList(0, n));
    }

    /** 出现次数最高的后缀，无则 null。 */
    public Candidate topSuffix(){
        Candidate best = null;
        for(Entry e : suffixes.values()){
            if(best == null || e.count > best.count || (e.count == best.count && e.lastUsed > best.lastUsed)){
                best = new Candidate(e.text, e.count, e.lastUsed);
            }
        }
        return best;
    }

    /** 使用次数最高的颜色标签，无则 null。 */
    public String topColor(){
        String best = null;
        int bestCount = 0;
        for(ObjectMap.Entry<String, Integer> e : colors){
            if(e.value > bestCount){
                bestCount = e.value;
                best = e.key;
            }
        }
        return best;
    }

    public void save(Fi file){
        file.parent().mkdirs();
        file.writeString(serialize());
    }

    public String exportString(){
        return serialize();
    }

    /** 从导出的字符串导入，替换当前词库；返回导入的条目数（消息+后缀+颜色）。 */
    public int importString(String content){
        WordLibrary parsed = parse(content == null ? "" : content);
        this.totalSent = parsed.totalSent;
        this.messages.clear();
        this.messages.putAll(parsed.messages);
        this.suffixes.clear();
        this.suffixes.putAll(parsed.suffixes);
        this.colors.clear();
        this.colors.putAll(parsed.colors);
        return messages.size + suffixes.size + colors.size;
    }

    private String serialize(){
        StringBuilder sb = new StringBuilder();
        sb.append("quick-complete-wordlib-v1\n");
        sb.append("[STATS]\n");
        sb.append("totalSent=").append(totalSent).append('\n');
        sb.append("[MESSAGES]\n");
        for(Entry e : messages.values()){
            sb.append(e.count).append('\t').append(e.lastUsed).append('\t').append(encode(e.text)).append('\n');
        }
        sb.append("[SUFFIXES]\n");
        for(Entry e : suffixes.values()){
            sb.append(e.count).append('\t').append(e.lastUsed).append('\t').append(encode(e.text)).append('\n');
        }
        sb.append("[COLORS]\n");
        for(ObjectMap.Entry<String, Integer> e : colors){
            sb.append(e.key).append('\t').append(e.value).append('\n');
        }
        return sb.toString();
    }

    public static WordLibrary load(Fi file){
        if(!file.exists()) return new WordLibrary();
        return parse(file.readString());
    }

    private static WordLibrary parse(String content){
        WordLibrary lib = new WordLibrary();
        ObjectMap<String, Entry> cur = null;
        boolean statsSection = false;
        boolean colorsSection = false;

        for(String line : content.split("\n", -1)){
            line = line.replace("\r", "");
            if("[STATS]".equals(line)){ cur = null; statsSection = true; colorsSection = false; continue; }
            if("[MESSAGES]".equals(line)){ cur = lib.messages; statsSection = false; colorsSection = false; continue; }
            if("[SUFFIXES]".equals(line)){ cur = lib.suffixes; statsSection = false; colorsSection = false; continue; }
            if("[COLORS]".equals(line)){ cur = null; statsSection = false; colorsSection = true; continue; }
            if(line.isEmpty()) continue;

            if(statsSection){
                if(line.startsWith("totalSent=")){
                    try{ lib.totalSent = Long.parseLong(line.substring("totalSent=".length())); }catch(Throwable ignored){}
                }
                continue;
            }
            if(colorsSection){
                String[] p = line.split("\t", 2);
                if(p.length == 2){
                    try{ lib.colors.put(p[0], Integer.parseInt(p[1])); }catch(Throwable ignored){}
                }
                continue;
            }
            if(cur == null) continue;

            String[] p = line.split("\t", 3);
            if(p.length != 3) continue;
            try{
                int count = Integer.parseInt(p[0]);
                long last = Long.parseLong(p[1]);
                String text = decode(p[2]);
                cur.put(text, new Entry(text, count, last));
            }catch(Throwable ignored){
            }
        }
        return lib;
    }

    private static String encode(String s){
        return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String s){
        try{
            return new String(Base64.getDecoder().decode(s), StandardCharsets.UTF_8);
        }catch(Throwable e){
            return s;
        }
    }
}
