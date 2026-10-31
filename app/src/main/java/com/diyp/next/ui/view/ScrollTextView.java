package com.diyp.next.ui.view;

import android.content.Context;
import android.text.TextUtils;
import android.util.AttributeSet;
import androidx.appcompat.widget.AppCompatTextView;

public class ScrollTextView extends AppCompatTextView {

    public ScrollTextView(Context context) {
        super(context);
        init();
    }

    public ScrollTextView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public ScrollTextView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setEllipsize(TextUtils.TruncateAt.MARQUEE);
        setMarqueeRepeatLimit(-1); // -1 = marquee_forever
        setSingleLine(true);
        setSelected(true);
    }

    @Override
    public boolean isFocused() {
        // Always return true to enable continuous marquee scrolling on TV and phone
        return true;
    }
}
