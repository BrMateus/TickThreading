package me.nallar.tickthreading.minecraft;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;
import cpw.mods.fml.common.network.NetworkMod;
import me.nallar.tickthreading.Log;
import me.nallar.tickthreading.minecraft.commands.TPSCommand;
import me.nallar.tickthreading.minecraft.commands.TicksCommand;
import me.nallar.tickthreading.minecraft.entitylist.EntityList;
import me.nallar.tickthreading.minecraft.entitylist.LoadedEntityList;
import me.nallar.tickthreading.minecraft.entitylist.LoadedTileEntityList;
import me.nallar.tickthreading.patcher.PatchManager;
import me.nallar.tickthreading.util.FieldUtil;
import me.nallar.tickthreading.util.LocationUtil;
import me.nallar.tickthreading.util.PatchUtil;
import net.minecraft.command.ServerCommandManager;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraftforge.common.Configuration;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.Property;
import net.minecraftforge.event.ForgeSubscribe;
import net.minecraftforge.event.world.WorldEvent;

@SuppressWarnings ("WeakerAccess")
@Mod (modid = "TickThreading", name = "TickThreading", version = "1.0")
@NetworkMod (clientSideRequired = false, serverSideRequired = false)
public class TickThreading {
	private static final int loadedTileEntityFieldIndex = 2;
	private static final int loadedEntityFieldIndex = 0;
	public final boolean enabled;
	private int tickThreads = 0;
	private boolean enableEntityTickThreading = true;
	private boolean enableTileEntityTickThreading = true;
	private int regionSize = 16;
	private boolean variableTickRate = true;
	private boolean requirePatched = true;
	final Map<World, TickManager> managers = new HashMap<World, TickManager>();
	private DeadLockDetector deadLockDetector = null;
	private static TickThreading instance;

	public TickThreading() {
		if (requirePatched && PatchManager.shouldPatch(LocationUtil.getJarLocations())) {
			enabled = false;
			try {
				PatchUtil.writePatchRunners();
			} catch (IOException e) {
				Log.severe("Failed to write patch runners", e);
			}
		} else {
			enabled = true;
		}
	}

	@Mod.Init
	public void init(FMLInitializationEvent event) {
		if (enabled) {
			MinecraftForge.EVENT_BUS.register(this);
		}
		instance = this;
	}

	@Mod.PreInit
	public void preInit(FMLPreInitializationEvent event) {
		Configuration config = new Configuration(event.getSuggestedConfigurationFile());
		config.load();
		Property tickThreadsProperty = config.get(Configuration.CATEGORY_GENERAL, "tickThreads", tickThreads);
		tickThreadsProperty.comment = "number of threads to use to tick. 0 = automatic (number of cores)";
		Property enableEntityTickThreadingProperty = config.get(Configuration.CATEGORY_GENERAL, "enableEntityTickThreading", enableEntityTickThreading);
		enableEntityTickThreadingProperty.comment = "Whether entity ticks should be threaded";
		Property enableTileEntityTickThreadingProperty = config.get(Configuration.CATEGORY_GENERAL, "enableTileEntityTickThreading", enableTileEntityTickThreading);
		enableTileEntityTickThreadingProperty.comment = "Whether tile entity ticks should be threaded";
		Property regionSizeProperty = config.get(Configuration.CATEGORY_GENERAL, "regionSize", regionSize);
		regionSizeProperty.comment = "width/length of tick regions, specified in blocks.";
		Property variableTickRateProperty = config.get(Configuration.CATEGORY_GENERAL, "variableRegionTickRate", variableTickRate);
		variableTickRateProperty.comment = "Allows tick rate to vary per region so that each region uses at most 50ms on average per tick.";
		Property ticksCommandName = config.get(Configuration.CATEGORY_GENERAL, "ticksCommandName", TicksCommand.name);
		ticksCommandName.comment = "Name of the command to be used for performance stats. Defaults to ticks.";
		Property tpsCommandName = config.get(Configuration.CATEGORY_GENERAL, "tpsCommandName", TPSCommand.name);
		tpsCommandName.comment = "Name of the command to be used for TPS reports.";
		Property requirePatchedProperty = config.get(Configuration.CATEGORY_GENERAL, "requirePatched", requirePatched);
		requirePatchedProperty.comment = "If the server must be patched to run with TickThreading";
		config.save();

		tickThreads = tickThreadsProperty.getInt(tickThreads);
		enableEntityTickThreading = enableEntityTickThreadingProperty.getBoolean(enableEntityTickThreading);
		enableTileEntityTickThreading = enableTileEntityTickThreadingProperty.getBoolean(enableTileEntityTickThreading);
		regionSize = regionSizeProperty.getInt(regionSize);
		variableTickRate = variableTickRateProperty.getBoolean(variableTickRate);
		TicksCommand.name = ticksCommandName.value;
		TPSCommand.name = tpsCommandName.value;
		requirePatched = requirePatchedProperty.getBoolean(requirePatched);
	}

	@Mod.ServerStarting
	public void serverStarting(FMLServerStartingEvent event) {
		if (enabled) {
			ServerCommandManager serverCommandManager = (ServerCommandManager) event.getServer().getCommandManager();
			serverCommandManager.registerCommand(new TicksCommand());
			serverCommandManager.registerCommand(new TPSCommand());
		} else {
			Log.severe("TickThreading is disabled, because your server has not been patched!" +
					"\nTo patch your server, simply run the PATCHME.bat/sh file in your server directory" +
					"\nAlternatively, you can try to run without patching, just edit the config. Probably won't end well.");
		}
	}

	@ForgeSubscribe
	public void onWorldLoad(WorldEvent.Load event) {
		TickManager manager = new TickManager(event.world, regionSize, tickThreads);
		manager.setVariableTickRate(variableTickRate);
		try {
			if (enableTileEntityTickThreading) {
				Field loadedTileEntityField = FieldUtil.getFields(World.class, List.class)[loadedTileEntityFieldIndex];
				new LoadedTileEntityList<TileEntity>(event.world, loadedTileEntityField, manager);
			}
			if (enableEntityTickThreading) {
				Field loadedEntityField = FieldUtil.getFields(World.class, List.class)[loadedEntityFieldIndex];
				new LoadedEntityList<TileEntity>(event.world, loadedEntityField, manager);
			}
			Log.info("Threading initialised for world " + Log.name(event.world));
			managers.put(event.world, manager);
		} catch (Exception e) {
			Log.severe("Failed to initialise threading for world " + Log.name(event.world), e);
		}
		if (deadLockDetector == null) {
			deadLockDetector = new DeadLockDetector(managers);
		}
	}

	@ForgeSubscribe
	public void onWorldUnload(WorldEvent.Unload event) {
		try {
			managers.get(event.world).unload();
			managers.remove(event.world);
			if (enableTileEntityTickThreading) {
				Field loadedTileEntityField = FieldUtil.getFields(World.class, List.class)[loadedTileEntityFieldIndex];
				Object loadedTileEntityList = loadedTileEntityField.get(event.world);
				if (!(loadedTileEntityList instanceof EntityList)) {
					Log.severe("Looks like another mod broke TickThreaded tile entities in world: " + Log.name(event.world));
				}
			}
			if (enableEntityTickThreading) {
				Field loadedEntityField = FieldUtil.getFields(World.class, List.class)[loadedEntityFieldIndex];
				Object loadedEntityList = loadedEntityField.get(event.world);
				if (!(loadedEntityList instanceof EntityList)) {
					Log.severe("Looks like another mod broke TickThreaded entities in world: " + Log.name(event.world));
				}
			}
		} catch (Exception e) {
			Log.severe("Probable memory leak, failed to unload threading for world " + Log.name(event.world), e);
		}
	}

	public TickManager getManager(World world) {
		return managers.get(world);
	}

	public List<TickManager> getManagers() {
		return new ArrayList<TickManager>(managers.values());
	}

	public static TickThreading instance() {
		return instance;
	}
}
