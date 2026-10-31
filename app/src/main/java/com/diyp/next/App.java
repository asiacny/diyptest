package com.diyp.next;

import android.app.Application;
import com.diyp.next.data.repository.SettingsRepository;

public class App extends Application {
    private static App instance;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        com.diyp.next.util.CrashHandler.init(this);
        SettingsRepository.init(this);
        com.diyp.next.util.BenchMarkManager.init(this);
    }

    public static App getInstance() {
        return instance;
    }
}
