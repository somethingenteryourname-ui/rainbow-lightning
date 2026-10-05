package me.rainbowlightning;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class RainbowLightningPlugin extends JavaPlugin {

    private RodItem rodItem;
    private ScorchManager scorchManager;
    private BeamManager beamManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        rodItem = new RodItem(this);
        scorchManager = new ScorchManager(this);
        beamManager = new BeamManager(this, rodItem, scorchManager);

        getServer().getPluginManager().registerEvents(beamManager, this);

        RodCommand command = new RodCommand(this, rodItem);
        PluginCommand pc = getCommand("rainbowrod");
        if (pc != null) {
            pc.setExecutor(command);
            pc.setTabCompleter(command);
        }

        reloadSettings();
        beamManager.start();
        scorchManager.start();
    }

    @Override
    public void onDisable() {
        if (beamManager != null) beamManager.stopAll();
        if (scorchManager != null) scorchManager.restoreAll();
    }

    public void reloadSettings() {
        reloadConfig();
        beamManager.loadSettings(getConfig());
        scorchManager.loadSettings(getConfig());
    }
}
