package com.diyp.next.util;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Process;
import android.util.Log;
import com.diyp.next.ui.CrashReportActivity;
import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class CrashHandler implements Thread.UncaughtExceptionHandler {
    private static final String TAG = "DIYP_CRASH";
    private static CrashHandler instance;
    private final Context context;
    private final Thread.UncaughtExceptionHandler defaultHandler;

    public static synchronized void init(Context context) {
        if (instance == null) {
            instance = new CrashHandler(context);
        }
    }

    private CrashHandler(Context context) {
        this.context = context.getApplicationContext();
        this.defaultHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(this);
    }

    @Override
    public void uncaughtException(Thread thread, Throwable ex) {
        try {
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            ex.printStackTrace(pw);
            String stackTrace = sw.toString();
            Log.e(TAG, "FATAL UNCAUGHT EXCEPTION on thread " + thread.getName() + ":\n" + stackTrace);

            saveCrashLog(stackTrace);

            Intent intent = new Intent(context, CrashReportActivity.class);
            intent.putExtra("crash_info", stackTrace);
            intent.putExtra("thread_name", thread.getName());
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            context.startActivity(intent);

            Process.killProcess(Process.myPid());
            System.exit(10);
        } catch (Throwable t) {
            Log.e(TAG, "Error handling crash", t);
            if (defaultHandler != null) {
                defaultHandler.uncaughtException(thread, ex);
            }
        }
    }

    private void saveCrashLog(String stackTrace) {
        try {
            File dir = context.getExternalFilesDir(null);
            if (dir == null) dir = context.getFilesDir();
            File logFile = new File(dir, "crash.log");
            FileWriter fw = new FileWriter(logFile, false);
            fw.write("Time: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date()) + "\n");
            fw.write("Device: " + Build.MANUFACTURER + " " + Build.MODEL + " (Android " + Build.VERSION.RELEASE + ", API " + Build.VERSION.SDK_INT + ")\n\n");
            fw.write(stackTrace);
            fw.flush();
            fw.close();
        } catch (Exception ignored) {}
    }
}
