package com.diyp.next.ui.dialog;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.diyp.next.R;

public class OptionSelectionDialog {
    public interface OnSelectedListener {
        void onSelected(int index, String text);
    }

    public static void show(Context context, String title, String[] items, int selectedIndex, OnSelectedListener listener) {
        if (context == null || items == null || items.length == 0) return;
        if (context instanceof android.app.Activity) {
            android.app.Activity act = (android.app.Activity) context;
            if (act.isFinishing() || act.isDestroyed()) return;
        }

        Dialog dialog = new Dialog(context, R.style.DialogTheme);
        dialog.setContentView(R.layout.dialog_option_selection);
        dialog.setCanceledOnTouchOutside(true);

        Window window = dialog.getWindow();
        if (window != null) {
            window.setGravity(Gravity.CENTER);
            WindowManager.LayoutParams params = window.getAttributes();
            params.width = (int) (context.getResources().getDisplayMetrics().density * 300);
            params.height = WindowManager.LayoutParams.WRAP_CONTENT;
            window.setAttributes(params);
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }

        TextView tvTitle = dialog.findViewById(R.id.tv_option_title);
        if (tvTitle != null && title != null) {
            tvTitle.setText(title);
        }

        LinearLayout container = dialog.findViewById(R.id.layout_option_container);
        if (container != null) {
            View initialFocusView = null;
            float density = context.getResources().getDisplayMetrics().density;

            for (int i = 0; i < items.length; i++) {
                final int index = i;
                final String itemText = items[i];

                TextView itemView = new TextView(context);
                itemView.setTextSize(14f);
                itemView.setTextColor(Color.WHITE);
                itemView.setBackgroundResource(R.drawable.selector_item_focus);
                itemView.setPadding(
                        (int) (14 * density),
                        (int) (10 * density),
                        (int) (14 * density),
                        (int) (10 * density)
                );
                itemView.setFocusable(true);
                itemView.setClickable(true);

                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                );
                lp.bottomMargin = (int) (4 * density);
                itemView.setLayoutParams(lp);

                boolean isSelected = (index == selectedIndex);
                if (isSelected) {
                    itemView.setTextColor(context.getResources().getColor(R.color.colorAccent));
                    itemView.setText("✓  " + itemText);
                    initialFocusView = itemView;
                } else {
                    itemView.setText("    " + itemText);
                }

                itemView.setOnClickListener(v -> {
                    dialog.dismiss();
                    if (listener != null) {
                        listener.onSelected(index, itemText);
                    }
                });

                container.addView(itemView);
            }

            if (initialFocusView != null) {
                final View focusTarget = initialFocusView;
                focusTarget.post(focusTarget::requestFocus);
            }
        }

        try {
            dialog.show();
        } catch (Exception ignored) {}
    }
}