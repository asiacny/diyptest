package com.diyp.next.ui.dialog;

import android.app.Dialog;
import android.content.Context;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import androidx.annotation.NonNull;
import com.diyp.next.R;

public class CustomUrlDialog extends Dialog {

    public interface OnUrlConfirmListener {
        void onConfirm(String url);
    }

    public CustomUrlDialog(@NonNull Context context, String title, String currentUrl, OnUrlConfirmListener listener) {
        super(context, R.style.DialogTheme);
        setContentView(R.layout.dialog_settings); // We will provide a dedicated layout or build cleanly
    }

    public static void show(Context context, String title, String currentUrl, OnUrlConfirmListener listener) {
        if (context == null) return;
        if (context instanceof android.app.Activity) {
            android.app.Activity act = (android.app.Activity) context;
            if (act.isFinishing() || act.isDestroyed()) return;
        }
        Dialog dialog = new Dialog(context, R.style.DialogTheme);
        android.widget.LinearLayout layout = new android.widget.LinearLayout(context);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        layout.setBackgroundResource(R.drawable.bg_banner_rounded);
        layout.setPadding(40, 40, 40, 40);

        TextView tvTitle = new TextView(context);
        tvTitle.setText(title);
        tvTitle.setTextSize(18);
        tvTitle.setTextColor(context.getResources().getColor(R.color.colorAccent));
        layout.addView(tvTitle);

        EditText etUrl = new EditText(context);
        etUrl.setText(currentUrl != null ? currentUrl : "");
        etUrl.setHint("http://...");
        etUrl.setTextColor(context.getResources().getColor(R.color.text_white));
        etUrl.setHintTextColor(context.getResources().getColor(R.color.text_gray));
        etUrl.setSingleLine(true);
        android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 30, 0, 30);
        layout.addView(etUrl, lp);

        android.widget.LinearLayout btnLayout = new android.widget.LinearLayout(context);
        btnLayout.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        btnLayout.setGravity(android.view.Gravity.END);

        Button btnCancel = new Button(context);
        btnCancel.setText("取消");
        btnCancel.setTextColor(context.getResources().getColor(R.color.text_gray));
        btnCancel.setBackgroundResource(R.drawable.selector_item_bg);
        btnCancel.setOnClickListener(v -> dialog.dismiss());
        btnLayout.addView(btnCancel);

        Button btnSave = new Button(context);
        btnSave.setText("保存");
        btnSave.setTextColor(context.getResources().getColor(R.color.colorAccent));
        btnSave.setBackgroundResource(R.drawable.selector_item_bg);
        btnSave.setOnClickListener(v -> {
            if (listener != null) {
                listener.onConfirm(etUrl.getText().toString().trim());
            }
            dialog.dismiss();
        });
        btnLayout.addView(btnSave);

        layout.addView(btnLayout);

        dialog.setContentView(layout);
        try {
            dialog.show();
        } catch (Exception ignored) {}
    }
}
