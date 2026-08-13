package quickcomplete;

import arc.Core;
import arc.files.Fi;
import mindustry.Vars;
import mindustry.graphics.Pal;
import mindustry.ui.dialogs.SettingsMenuDialog.SettingsTable;

import java.util.List;

/** 设置菜单：统计、榜单、导入/导出、打开词库文件、快捷键说明。 */
public class SettingsMenu{

    public static void install(WordLibrary library, Fi dataFile){
        if(Vars.ui == null || Vars.ui.settings == null) return;
        Vars.ui.settings.addCategory("快速补全", table -> build(table, library, dataFile));
    }

    private static void build(SettingsTable table, WordLibrary library, Fi dataFile){
        table.left();
        table.margin(8f);

        table.add("聊天补全统计").color(Pal.accent).padBottom(8f).row();

        table.add("总发送消息数：").left();
        table.label(() -> String.valueOf(library.totalSent)).left().row();

        table.add("消息库条目数：").left();
        table.label(() -> String.valueOf(library.messages.size)).left().row();

        table.add("最爱消息 Top3：").left().padTop(8f).row();
        table.label(() -> topMessagesText(library)).left().wrap().width(420f).row();

        table.add("最爱后缀：").left().padTop(8f);
        table.label(() -> topSuffixText(library)).left().row();

        table.add("最爱颜色：").left().padTop(8f);
        table.label(() -> topColorText(library)).left().row();

        table.button("导出词库（复制到剪贴板）", () -> {
            Core.app.setClipboardText(library.exportString());
            Vars.ui.showInfoToast("已复制词库到剪贴板", 3f);
        }).width(300f).pad(6f).padTop(12f).row();

        table.button("导入词库（从剪贴板）", () -> {
            String s = Core.app.getClipboardText();
            if(s == null || s.trim().isEmpty()){
                Vars.ui.showInfoToast("剪贴板为空", 3f);
                return;
            }
            int n = library.importString(s);
            library.save(dataFile);
            Vars.ui.showInfoToast("已导入 " + n + " 条词库", 3f);
        }).width(300f).pad(6f).row();

        table.button("打开词库文件夹", () -> {
            Core.app.openFolder(dataFile.parent().absolutePath());
        }).width(300f).pad(6f).row();

        table.add("部分匹配最少字数：").left().padTop(12f);
        table.label(() -> ChatCompleter.minMatchLen() + " 字").left().row();
        table.slider(2, 6, 1, ChatCompleter.minMatchLen(), v -> {
            Core.settings.put("quick-complete.minmatchlen", (int)Math.round(v));
            Core.settings.forceSave();
        }).width(300f).pad(6f).row();

        table.check("部分匹配 Tab 仅粘贴不替换", ChatCompleter.partialPaste(), v -> {
            Core.settings.put("quick-complete.partialpaste", v);
            Core.settings.forceSave();
        }).left().pad(6f).row();

        table.add("快捷键说明：").color(Pal.accent).padTop(14f).padBottom(4f).row();
        table.add("Tab 补全 / ↑↓ 选择 / Shift+Tab 保存或删除当前项").left().row();
        table.add("光标末尾 → 后缀补全 / 光标最前 ← 颜色补全").left().row();
    }

    private static String topMessagesText(WordLibrary library){
        List<Candidate> top = library.topMessages(3);
        if(top.isEmpty()) return "（暂无）";
        StringBuilder sb = new StringBuilder();
        for(int i = 0; i < top.size(); i++){
            Candidate c = top.get(i);
            sb.append(i + 1).append(". ").append(c.text).append("（").append(c.count).append("次）");
            if(i < top.size() - 1) sb.append("\n");
        }
        return sb.toString();
    }

    private static String topSuffixText(WordLibrary library){
        Candidate c = library.topSuffix();
        return c == null ? "（暂无）" : c.text + "（" + c.count + "次）";
    }

    private static String topColorText(WordLibrary library){
        String tag = library.topColor();
        if(tag == null) return "（暂无）";
        int n = library.colors.get(tag, 0);
        return "[" + tag + "]" + tag + "[]（" + n + "次）";
    }
}
