package com.yourname.nexo;

import org.bukkit.plugin.java.JavaPlugin;
import java.io.File;

public class NexoPlugin extends JavaPlugin {

    private File scriptsFolder;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        this.scriptsFolder = new File(getDataFolder(), "scripts");
        if (!this.scriptsFolder.exists()) {
            this.scriptsFolder.mkdirs();
        }

        loadAllScripts();
        getLogger().info("Nexo enabled! Loaded scripts from " + scriptsFolder.getPath());
    }

    public void loadAllScripts() {
        File[] files = scriptsFolder.listFiles((dir, name) -> name.endsWith(".nexo"));
        if (files == null) return;

        for (File scriptFile : files) {
            getLogger().info("Parsing script: " + scriptFile.getName());
            // TODO: Pass scriptFile to ScriptParser
        }
    }

    public File getScriptsFolder() {
        return scriptsFolder;
    }
}
