package com.diyp.next.ui;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Process;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public class CrashReportActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String crashInfo = getIntent().getStringExtra("crash_info");
        if (crashInfo == null || crashInfo.trim().isEmpty()) {
            crashInfo = "无异常堆栈详情";
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#181818"));
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);

        TextView tvTitle = new TextView(this);
        tvTitle.setText("【DIYP-Next 运行异常诊断】");
        tvTitle.setTextSize(18);
        tvTitle.setTextColor(Color.parseColor("#FF5252"));
        tvTitle.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(tvTitle);

        TextView tvDesc = new TextView(this);
        tvDesc.setText("程序捕获到未处理异常，已阻止直接闪退。请查看以下调用栈详情以便排查：");
        tvDesc.setTextSize(13);
        tvDesc.setTextColor(Color.parseColor("#CCCCCC"));
        tvDesc.setPadding(0, (int) (6 * getResources().getDisplayMetrics().density), 0, (int) (10 * getResources().getDisplayMetrics().density));
        root.addView(tvDesc);

        ScrollView scrollView = new ScrollView(this);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f);
        scrollView.setLayoutParams(scrollParams);
        scrollView.setBackgroundColor(Color.parseColor("#0D0D0D"));
        scrollView.setPadding(pad, pad, pad, pad);

        TextView tvCrash = new TextView(this);
        tvCrash.setText(crashInfo);
        tvCrash.setTextSize(12);
        tvCrash.setTextColor(Color.parseColor("#EEEEEE"));
        tvCrash.setTypeface(Typeface.MONOSPACE);
        scrollView.addView(tvCrash);
        root.addView(scrollView);

        LinearLayout btnLayout = new LinearLayout(this);
        btnLayout.setOrientation(LinearLayout.HORIZONTAL);
        btnLayout.setGravity(Gravity.END);
        btnLayout.setPadding(0, (int) (12 * getResources().getDisplayMetrics().density), 0, 0);

        Button btnRestart = new Button(this);
        btnRestart.setText("重新启动应用");
        btnRestart.setFocusable(true);
        btnRestart.requestFocus();
        btnRestart.setOnClickListener(v -> {
            Intent intent = new Intent(CrashReportActivity.this, PlayerActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            finish();
            Process.killProcess(Process.myPid());
        });
        btnLayout.addView(btnRestart);

        Button btnExit = new Button(this);
        btnExit.setText("退出");
        btnExit.setFocusable(true);
        btnExit.setOnClickListener(v -> {
            finish();
            Process.killProcess(Process.myPid());
            System.exit(0);
        });
        btnLayout.addView(btnExit);

        root.addView(btnLayout);
        setContentView(root);
    }
}
