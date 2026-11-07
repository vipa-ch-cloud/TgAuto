package com.v1pach.telescript.plugin;

import android.content.Context;
import android.content.SharedPreferences;

/** The only app surface a plugin gets. */
public interface PluginHost {
    int API_VERSION = 1;

    int apiVersion();

    /** Version name of the base app. */
    String appVersion();

    /** Application context (resources, system services). */
    Context context();

    /** Appends a line to the app's update log. */
    void log(String message);

    /** Preferences private to this plugin id. */
    SharedPreferences prefs();
}
