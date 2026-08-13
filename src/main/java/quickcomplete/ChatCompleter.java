package quickcomplete;

import arc.Core;
import arc.Events;
import arc.files.Fi;
import arc.input.InputProcessor;
import arc.input.KeyCode;
import arc.scene.ui.TextField;
import arc.scene.ui.layout.Scl;
import arc.util.Align;
import arc.util.Log;
import mindustry.Vars;
import mindustry.game.EventType.ClientChatEvent;
import mindustry.game.EventType.Trigger;
import mindustry.ui.fragments.ChatFragment;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 聊天补全核心：反射拿到 chatfield，管理三种补全模式、滚动列表与按键拦截。 */
public class ChatCompleter{

    public enum Mode{ STANDARD, SUFFIX, PREFIX }

    private static ChatCompleter instance;

    /** 候选列表可视行数。 */
    private static final int VISIBLE = 8;
    /** 长按上下键的连发：首次延迟 / 连发间隔（秒）。 */
    private static final float REPEAT_INITIAL = 0.4f, REPEAT_INTERVAL = 0.08f;

    /** 前置指令：`/指令 + 空格 + 文本` 时，前缀不计入词库，仅补全有效文本。 */
    private static final String[] CHAT_COMMANDS = {"/t", "/a", "/achat", "/r"};

    public static void init(){
        instance = new ChatCompleter();
        instance.setup();
    }

    /** 部分匹配要求的最少字数（设置里可调）。 */
    public static int minMatchLen(){
        return Math.max(1, Core.settings.getInt("quick-complete.minmatchlen", 3));
    }

    /** 部分匹配 Tab 是否「仅粘贴不替换」（默认替换全部文字）。 */
    public static boolean partialPaste(){
        return Core.settings.getBool("quick-complete.partialpaste", false);
    }

    private TextField chatfield;
    private Mode mode = Mode.STANDARD;
    private WordLibrary library;
    private CompleterOverlay overlay;
    private Fi dataFile;

    private List<Candidate> candidates = new ArrayList<>();
    private List<ColorTags.ColorEntry> colors = new ArrayList<>();
    private int focus = -1;      // 绝对下标（全列表）
    private int scrollStart = 0; // 可视窗口起始下标
    private int suffixBase = 0;  // 进入后缀模式时的文本长度

    private String lastText = "";
    private Mode lastMode = Mode.STANDARD;
    private String lastRawInput = "";
    private String curInput = "", curPrefix = "", curContent = "";

    private final Set<KeyCode> held = new HashSet<>();
    private float upRepeatTimer = -1, downRepeatTimer = -1;
    /** 历史翻页中：按 ↑/↓ 触发历史时抑制补全框，直到用户重新敲字。 */
    private boolean historyMode = false;

    private void setup(){
        ChatFragment chatfrag = Vars.ui.chatfrag;
        try{
            Field f = ChatFragment.class.getDeclaredField("chatfield");
            f.setAccessible(true);
            chatfield = (TextField)f.get(chatfrag);
        }catch(Throwable e){
            Log.err("[quick-complete] 无法反射 chatfield，补全功能已禁用: " + e);
            return;
        }
        chatfield.setFocusTraversal(false);

        dataFile = Core.settings.getDataDirectory().child("quick-complete").child("wordlib.dat");
        library = WordLibrary.load(dataFile);

        overlay = new CompleterOverlay(chatfield);
        overlay.attach();

        Events.on(ClientChatEvent.class, this::onChatSent);
        Events.run(Trigger.update, this::update);
        SettingsMenu.install(library, dataFile);

        Core.input.getInputMultiplexer().addProcessor(0, new InputProcessor(){
            public boolean keyDown(KeyCode keycode){ return onKeyDown(keycode); }
            public boolean keyUp(KeyCode keycode){ held.remove(keycode); return false; }
            public boolean keyTyped(char character){
                if(character >= ' ') historyMode = false;
                return false;
            }
            public boolean touchDown(int x, int y, int pointer, KeyCode button){ return false; }
            public boolean touchUp(int x, int y, int pointer, KeyCode button){ return false; }
            public boolean touchDragged(int x, int y, int pointer){ return false; }
            public boolean mouseMoved(int x, int y){ return false; }
            public boolean scrolled(float amountX, float amountY){ return false; }
        });
    }

    private void onChatSent(ClientChatEvent e){
        String msg = lastRawInput.isEmpty() ? e.message : lastRawInput;
        if(msg == null || msg.trim().isEmpty()) return;
        library.record(stripCommand(msg));
        library.save(dataFile);
    }

    private void update(){
        if(chatfield == null) return;
        if(!Vars.ui.chatfrag.shown()){
            if(mode != Mode.STANDARD || !candidates.isEmpty() || focus >= 0){
                mode = Mode.STANDARD;
                candidates.clear();
                colors.clear();
                focus = -1;
                scrollStart = 0;
            }
            historyMode = false;
            upRepeatTimer = -1;
            downRepeatTimer = -1;
            overlay.hide();
            return;
        }
        lastRawInput = chatfield.getText().trim();
        try{
            recompute();
        }catch(Throwable t){
            Log.err("[quick-complete] recompute 异常: " + t);
        }
        processRepeatKeys();
    }

    private void processRepeatKeys(){
        upRepeatTimer = processRepeat(KeyCode.up, upRepeatTimer, -1);
        downRepeatTimer = processRepeat(KeyCode.down, downRepeatTimer, +1);
    }

    private float processRepeat(KeyCode k, float timer, int delta){
        if(!held.contains(k) || !listVisible() || totalRows() <= 0) return -1;
        if(timer < 0){
            moveFocus(delta);
            return REPEAT_INITIAL;
        }
        float t = timer - Core.graphics.getDeltaTime();
        if(t <= 0){
            moveFocus(delta);
            return REPEAT_INTERVAL;
        }
        return t;
    }

    private void moveFocus(int delta){
        int total = totalRows();
        if(total <= 0) return;
        focus += delta;
        if(focus < 0) focus = 0;
        if(focus >= total) focus = total - 1;
        refreshOverlay();
    }

    private void recompute(){
        String text = chatfield.getText();
        int cursor = chatfield.getCursorPosition();
        boolean changed = !text.equals(lastText) || mode != lastMode;
        lastText = text;
        lastMode = mode;

        candidates = new ArrayList<>();
        colors = new ArrayList<>();
        curContent = "";
        curPrefix = "";

        if(!historyMode){
            String[] sc = splitCommand(text);
            String effText = sc == null ? text : sc[1];

            switch(mode){
                case STANDARD: {
                    if(effText.isEmpty()){
                        focus = -1;
                    }else{
                        candidates = arrange(library.queryMessages(effText, minMatchLen()));
                        if(changed) focus = defaultFocus();
                    }
                    curPrefix = effText;
                    break;
                }
                case SUFFIX: {
                    int base = Math.min(suffixBase, text.length());
                    String typed = text.substring(base);
                    candidates = arrange(library.querySuffixes(typed));
                    if(changed) focus = defaultFocus();
                    curPrefix = typed;
                    break;
                }
                case PREFIX: {
                    String search = text.substring(0, cursor);
                    curContent = text.substring(cursor);
                    ColorTags.ColorEntry h = ColorTags.parseHex(search);
                    if(h != null) colors.add(h);
                    colors.addAll(ColorTags.search(search));
                    if(changed) focus = 0;
                    break;
                }
            }
        }

        int total = totalRows();
        if(total == 0){
            focus = -1;
        }else if(focus < 0){
            focus = 0;
        }else if(focus >= total){
            focus = total - 1;
        }

        if(changed){
            scrollStart = (mode == Mode.PREFIX) ? 0 : Math.max(0, total - VISIBLE);
        }

        curInput = text;
        refreshOverlay();
    }

    private void refreshOverlay(){
        int total = totalRows();
        scrollStart = clampScroll(total);

        if(mode == Mode.PREFIX){
            int end = Math.min(scrollStart + VISIBLE, colors.size());
            List<ColorTags.ColorEntry> vis = new ArrayList<>(colors.subList(scrollStart, end));
            overlay.refresh(mode, new ArrayList<>(), vis, focus - scrollStart, curInput, curPrefix, curContent);
        }else{
            int end = Math.min(scrollStart + VISIBLE, candidates.size());
            List<Candidate> vis = new ArrayList<>(candidates.subList(scrollStart, end));
            overlay.refresh(mode, vis, new ArrayList<>(), focus - scrollStart, curInput, curPrefix, curContent);
        }
    }

    private int clampScroll(int total){
        if(total <= VISIBLE) return 0;
        int start = scrollStart;
        if(focus < start) start = focus;
        else if(focus >= start + VISIBLE) start = focus - VISIBLE + 1;
        return Math.max(0, Math.min(start, total - VISIBLE));
    }

    private int defaultFocus(){
        int n = candidates.size();
        if(n <= 0) return -1;
        if(n == 1) return 0;
        return n - 2; // 最高频（倒数第二行）
    }

    private int totalRows(){
        if(mode == Mode.PREFIX) return colors.size();
        return candidates.size();
    }

    /** 展示顺序：次数升序，但最高频放倒数第二行、次高频放最后一行。 */
    private static List<Candidate> arrange(List<Candidate> desc){
        int n = desc.size();
        if(n <= 2) return desc;
        List<Candidate> out = new ArrayList<>(n);
        for(int i = n - 1; i >= 2; i--) out.add(desc.get(i));
        out.add(desc.get(0));
        out.add(desc.get(1));
        return out;
    }

    private boolean onKeyDown(KeyCode k){
        boolean fresh = held.add(k);
        if(chatfield == null || !Vars.ui.chatfrag.shown()) return false;
        try{
            recompute();
        }catch(Throwable t){
            Log.err("[quick-complete] recompute 异常: " + t);
            return false;
        }

        switch(k){
            case up:
                if(listVisible()){
                    return true; // 焦点导航（焦点移动在 update 里做）
                }
                historyMode = true; // 历史翻页：抑制补全框
                return false;
            case down:
                if(listVisible()){
                    return true;
                }
                historyMode = true;
                return false;
            case tab:
                if(Core.input.shift()){
                    if(fresh) toggleSave();
                    return true;
                }
                if(!canComplete()) return false;
                if(fresh) complete();
                return true;
            case left:
                return onLeft(fresh);
            case right:
                return onRight(fresh);
            default:
                return false;
        }
    }

    private boolean listVisible(){
        if(mode == Mode.PREFIX) return totalRows() >= 1;
        if(candidates.size() >= 2) return true;
        return candidates.size() == 1 && candidates.get(0).matchType != Candidate.MatchType.PREFIX;
    }

    private boolean canComplete(){
        if(mode == Mode.PREFIX) return totalRows() >= 1 && focus >= 0;
        return candidates.size() >= 1 && focus >= 0;
    }

    private boolean onLeft(boolean fresh){
        if(!fresh) return false;
        if(mode == Mode.STANDARD){
            if(chatfield.getCursorPosition() == 0){
                mode = Mode.PREFIX;
                Log.info("[quick-complete] 进入前缀补全");
                return true;
            }
            return false;
        }
        if(mode == Mode.SUFFIX){
            mode = Mode.STANDARD;
            Log.info("[quick-complete] 后缀 -> 标准");
            return true;
        }
        return false;
    }

    private boolean onRight(boolean fresh){
        if(!fresh) return false;
        if(mode == Mode.STANDARD){
            if(chatfield.getCursorPosition() >= chatfield.getText().length()){
                suffixBase = chatfield.getText().length();
                mode = Mode.SUFFIX;
                Log.info("[quick-complete] 进入后缀补全");
                return true;
            }
            return false;
        }
        if(mode == Mode.PREFIX){
            mode = Mode.STANDARD;
            Log.info("[quick-complete] 前缀 -> 标准");
            return true;
        }
        return false;
    }

    private void complete(){
        switch(mode){
            case STANDARD: {
                if(focus >= 0 && focus < candidates.size()){
                    Candidate c = candidates.get(focus);
                    String full = c.text;
                    String[] sc = splitCommand(chatfield.getText());
                    String cmdPart = sc == null ? "" : sc[0];
                    String newText;
                    if(c.matchType == Candidate.MatchType.PARTIAL && partialPaste()){
                        // 仅粘贴不替换：保留当前输入，追加完整句子
                        newText = chatfield.getText() + full;
                    }else{
                        newText = cmdPart + full;
                    }
                    chatfield.setText(newText);
                    chatfield.setCursorPosition(newText.length());
                }
                break;
            }
            case SUFFIX: {
                if(focus >= 0 && focus < candidates.size()){
                    String token = candidates.get(focus).text;
                    String text = chatfield.getText();
                    int base = Math.min(suffixBase, text.length());
                    String newText = text.substring(0, base) + token;
                    chatfield.setText(newText);
                    chatfield.setCursorPosition(newText.length());
                }
                mode = Mode.STANDARD;
                break;
            }
            case PREFIX: {
                if(focus >= 0 && focus < colors.size()){
                    String tag = colors.get(focus).insertTag();
                    library.recordColor(tag);
                    library.save(dataFile);

                    String text = chatfield.getText();
                    int cursor = chatfield.getCursorPosition();
                    String rest = text.substring(cursor);
                    String insert = "[" + tag + "]";
                    chatfield.setText(insert + rest);
                    chatfield.setCursorPosition((insert + rest).length());
                    mode = Mode.STANDARD;
                }
                break;
            }
        }
        recompute();
    }

    /** Shift+Tab：标准模式保存/删除当前输入项；后缀模式删除聚焦的后缀项。 */
    private void toggleSave(){
        if(mode == Mode.SUFFIX){
            if(focus >= 0 && focus < candidates.size()){
                String token = candidates.get(focus).text;
                boolean removed = library.deleteSuffix(token);
                library.save(dataFile);
                showToast(removed ? "已删除后缀「" + token + "」" : "后缀「" + token + "」不存在");
                recompute();
            }
            return;
        }
        if(mode == Mode.PREFIX) return; // 颜色为静态表，不可删
        String text = chatfield.getText();
        String eff = stripCommand(text);
        if(eff == null || eff.isEmpty()) return;
        boolean deleted = library.togglePin(eff);
        library.save(dataFile);
        showToast(deleted ? "已删除「" + eff + "」" : "已保存「" + eff + "」");
        recompute();
    }

    /** 在输入栏附近显示提示。 */
    private void showToast(String msg){
        Vars.ui.showInfoPopup(msg, "quickcomplete-toast", 3f, Align.bottomLeft, 0, (int)Scl.scl(8), (int)Scl.scl(44), 0);
    }

    /** 剔除前置指令，返回有效文本；无前置指令时返回原文本。 */
    private static String stripCommand(String text){
        String[] sc = splitCommand(text);
        return sc == null ? text : sc[1];
    }

    /** 若文本以 `/指令 + 空格` 开头，返回 {指令前缀(含空格), 有效文本}，否则 null。 */
    private static String[] splitCommand(String text){
        if(text == null) return null;
        for(String cmd : CHAT_COMMANDS){
            if(text.startsWith(cmd) && text.length() > cmd.length() && text.charAt(cmd.length()) == ' '){
                return new String[]{cmd + " ", text.substring(cmd.length() + 1)};
            }
        }
        return null;
    }
}
