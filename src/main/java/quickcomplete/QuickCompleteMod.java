package quickcomplete;

import mindustry.mod.Mod;

public class QuickCompleteMod extends Mod{

    @Override
    public void init(){
        ChatCompleter.init();
    }
}
