package com.firefoxdl.mod;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 模块设置页面：配置下载目录（相对 /storage/emulated/0 的文件夹名）。
 * 使用纯框架控件，无 androidx 依赖。
 */
public class SettingsActivity extends Activity {

    private EditText folderInput;
    private boolean worldReadablePrefs;

    /**
     * 读取模块偏好。按 LSPosed 官方做法：
     * 声明了 xposedsharedprefs 后，LSPosed 会把模块注入到自身进程并 hook
     * ContextImpl.checkMode，此时 MODE_WORLD_READABLE 不再抛异常，
     * 且只有这种模式 LSPosed 才会把偏好文件置为 Firefox 进程可读。
     * 若 LSPosed 未注入（版本过旧或未生效），回退 MODE_PRIVATE 保证页面可用。
     */
    private SharedPreferences prefs() {
        try {
            SharedPreferences p = getSharedPreferences(XposedEntry.PREFS_NAME, Context.MODE_WORLD_READABLE);
            worldReadablePrefs = true;
            return p;
        } catch (SecurityException e) {
            worldReadablePrefs = false;
            return getSharedPreferences(XposedEntry.PREFS_NAME, Context.MODE_PRIVATE);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            buildLayout();
        } catch (Throwable t) {
            // 防御：任何异常都显示出来而不是闪退，方便排查
            TextView err = new TextView(this);
            err.setTextSize(15);
            err.setText("设置页加载失败：" + t);
            setContentView(err);
        }
    }

    private void buildLayout() {
        int pad = dp(16);
        int padSmall = dp(8);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText(R.string.setting_title);
        title.setTextSize(20);
        title.setTypeface(null, Typeface.BOLD);
        root.addView(title, lp());

        folderInput = new EditText(this);
        folderInput.setSingleLine(true);
        folderInput.setHint(R.string.setting_hint);
        root.addView(folderInput, lp());

        TextView hint = new TextView(this);
        hint.setText(R.string.setting_hint_text);
        hint.setTextSize(13);
        hint.setPadding(0, padSmall, 0, padSmall);
        root.addView(hint, lp());

        TextView status = new TextView(this);
        status.setTextSize(12);
        prefs();
        if (worldReadablePrefs) {
            status.setText(R.string.status_ok);
            status.setTextColor(0xFF2E7D32);
        } else {
            status.setText(R.string.status_fail);
            status.setTextColor(0xFFC62828);
        }
        root.addView(status, lp());

        Button save = new Button(this);
        save.setText(R.string.setting_save);
        save.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveFolder();
            }
        });
        root.addView(save, lp());

        setContentView(root);

        SharedPreferences prefs = prefs();
        folderInput.setText(prefs.getString(XposedEntry.KEY_FOLDER, ""));
    }

    private void saveFolder() {
        String value = folderInput.getText().toString().trim();
        if (value.isEmpty()) {
            Toast.makeText(this, R.string.invalid, Toast.LENGTH_SHORT).show();
            return;
        }
        prefs().edit()
                .putString(XposedEntry.KEY_FOLDER, value)
                .commit();
        Toast.makeText(this, R.string.saved, Toast.LENGTH_LONG).show();
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(folderInput.getWindowToken(), 0);
        }
        finish();
    }

    private int dp(int value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }

    private LinearLayout.LayoutParams lp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(8), 0, dp(8));
        return lp;
    }
}
