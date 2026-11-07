package com.v1pach.telescript.plugin;

import android.content.Context;
import android.view.View;
import java.util.List;

/**
 * Entry point of a .tgadex plugin (API version {@link PluginHost#API_VERSION}). The manifest "entry" names a
 * public class with a public no-arg constructor implementing this interface. Plugin code runs inside the app
 * process with the app's privileges, which is why only packages signed by the official key load automatically.
 * This API is deliberately small and stable: adding methods requires a new API version.
 */
public interface TgadexPlugin {
    /** Called once after loading, on the main thread. Keep it fast; throwing counts as a failed start. */
    void onLoad(PluginHost host);

    /** Screens the plugin offers (shown under Settings > Updates > Plugin). May be empty. */
    List<PluginScreen> screens();

    /** Builds the view for one of {@link #screens()}. {@code context} is the hosting activity. */
    View createView(Context context, String screenId);

    /** The plugin is being unloaded (rollback/removal happens at next start, so this is rarely used). */
    void onUnload();
}
