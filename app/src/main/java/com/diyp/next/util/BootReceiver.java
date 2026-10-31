package com.diyp.next.util;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import com.diyp.next.data.repository.SettingsRepository;
import com.diyp.next.ui.PlayerActivity;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            try {
                SettingsRepository.init(context);
                if (SettingsRepository.get().isBootStart()) {
                    Intent i = new Intent(context, PlayerActivity.class);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(i);
                }
            } catch (Exception e) {
                android.util.Log.e("BootReceiver", "Failed to start PlayerActivity on boot", e);
            }
        }
    }
}
