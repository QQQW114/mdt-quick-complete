package quickcomplete;

/** 一个补全候选：文本 + 出现次数 + 最近使用时间 + 匹配类型。 */
public class Candidate{

    public enum MatchType{
        /** 字面前缀匹配（可做 ghost 预览）。 */
        PREFIX,
        /** 忽略颜色前缀后的前缀匹配（仅列表，不做 ghost）。 */
        COLOR,
        /** 部分匹配（包含，长度>=阈值；仅列表）。 */
        PARTIAL
    }

    public final String text;
    public final int count;
    public final long lastUsed;
    public final MatchType matchType;

    public Candidate(String text, int count, long lastUsed){
        this(text, count, lastUsed, MatchType.PREFIX);
    }

    public Candidate(String text, int count, long lastUsed, MatchType matchType){
        this.text = text;
        this.count = count;
        this.lastUsed = lastUsed;
        this.matchType = matchType;
    }
}
