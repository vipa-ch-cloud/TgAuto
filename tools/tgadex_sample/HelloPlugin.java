package com.v1pach.sample;

import android.content.Context;
import android.view.View;
import android.widget.TextView;
import com.v1pach.telescript.plugin.PluginHost;
import com.v1pach.telescript.plugin.PluginScreen;
import com.v1pach.telescript.plugin.TgadexPlugin;
import java.util.Collections;
import java.util.List;

/** Minimal .tgadex plugin: one screen showing a line of text and how many times it was opened. */
public class HelloPlugin implements TgadexPlugin {
    private PluginHost host;

    @Override public void onLoad(PluginHost host) {
        this.host = host;
        host.log("hello plugin loaded, api " + host.apiVersion() + ", app " + host.appVersion());
    }

    @Override public List<PluginScreen> screens() {
        return Collections.singletonList(new PluginScreen("hello", "Hello plugin"));
    }

    @Override public View createView(Context context, String screenId) {
        int n = host.prefs().getInt("opened", 0) + 1;
        host.prefs().edit().putInt("opened", n).apply();
        TextView t = new TextView(context);
        t.setText("Hello from a signed .tgadex plugin. Opened " + n + " time(s).");
        t.setPadding(32, 32, 32, 32);
        return t;
    }

    @Override public void onUnload() { }
}
