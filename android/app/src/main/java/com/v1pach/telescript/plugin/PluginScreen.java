package com.v1pach.telescript.plugin;

/** A screen a plugin contributes: a stable id and a human title. */
public final class PluginScreen {
    public final String id;
    public final String title;

    public PluginScreen(String id, String title) {
        this.id = id;
        this.title = title;
    }
}
