package red.mohist.configuration;

import net.minecraftforge.cauldron.CauldronHooks;
import net.minecraftforge.cauldron.TileEntityCache;
import net.minecraftforge.cauldron.command.TileEntityCommand;
import org.bukkit.configuration.file.YamlConfiguration;
import red.mohist.Mohist;

public class TileEntityConfig extends ConfigBase
{
    private final String HEADER = "This is the main configuration file for TileEntities.\n"
            + "\n"
            + "Home: https://www.mohist.red/\n";
    
    /* ======================================================================== */
    public final BoolSetting skipTileEntityTicks = new BoolSetting(this, "settings.skip-tileentity-ticks", true, "If enabled, turns on tileentity tick skip feature when no players are near.");
    public final BoolSetting enableTECanUpdateWarning = new BoolSetting(this, "debug.enable-te-can-update-warning", false, "Set true to detect which tileentities should not be ticking.");
    public final BoolSetting enableTEInventoryWarning = new BoolSetting(this, "debug.enable-te-inventory-warning", false, "Set true to detect which tileentities with inventory failed to detect size for Bukkit's InventoryType enum. Note: This may detect a false-positive.");
    public final BoolSetting enableTEPlaceWarning = new BoolSetting(this, "debug.enable-te-place-warning", false, "Warn when a mod requests tile entity from a block that doesn't support one");
    public final BoolSetting preventInvalidTileEntityUpdates = new BoolSetting(this, "settings.prevent-invalid-tileentity-updates", true, "Used to determine if a tileentity should tick and if not the TE is added to a ban list. Note: This should help improve performance.");
    /* ======================================================================== */

    public TileEntityConfig()
    {
        super("tileentities.yml", "tileentities");
        init();
    }

    @Override
    public void addCommands()
    {
        commands.put(this.commandName, new TileEntityCommand());
    }

    public void init()
    {
        settings.put(skipTileEntityTicks.path, skipTileEntityTicks);
        settings.put(enableTECanUpdateWarning.path, enableTECanUpdateWarning);
        settings.put(enableTEInventoryWarning.path, enableTEInventoryWarning);
        settings.put(enableTEPlaceWarning.path, enableTEPlaceWarning);
        settings.put(preventInvalidTileEntityUpdates.path, preventInvalidTileEntityUpdates);
        load();
    }

    @Override
    public void load()
    {
        try
        {
            config = YamlConfiguration.loadConfiguration(configFile);
            StringBuilder header = new StringBuilder(HEADER + "\n");
            for (Setting toggle : settings.values())
            {
                if (!toggle.description.equals("")) {
                    header.append("Setting: ").append(toggle.path).append(" Default: ").append(toggle.def).append("   # ").append(toggle.description).append("\n");
                }

                config.addDefault(toggle.path, toggle.def);
                settings.get(toggle.path).setValue(config.getString(toggle.path));
            }
            config.options().header(header.toString());
            config.options().copyDefaults(true);

            version = getInt("config-version", 1);
            set("config-version", 1);

            for (TileEntityCache teCache : CauldronHooks.tileEntityCache.values())
            {
                teCache.tickNoPlayers = config.getBoolean( "world-settings." + teCache.worldName + "." + teCache.configPath + ".tick-no-players", config.getBoolean( "world-settings.default." + teCache.configPath + ".tick-no-players") );
                teCache.tickInterval = config.getInt( "world-settings." + teCache.worldName + "." + teCache.configPath + ".tick-interval", config.getInt( "world-settings.default." + teCache.configPath + ".tick-interval") );
            }
            this.saveWorldConfigs();
            this.save();
        }
        catch (Exception ex)
        {
            Mohist.LOGGER.error("Could not load " + this.configFile);
            ex.printStackTrace();
        }
    }
}
