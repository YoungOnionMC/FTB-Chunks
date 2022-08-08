package dev.ftb.mods.ftbchunks.client.map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.ftb.mods.ftbchunks.FTBChunks;
import dev.ftb.mods.ftbchunks.client.FTBChunksClient;
import dev.ftb.mods.ftbchunks.integration.RefreshMinimapIconsEvent;
import dev.ftb.mods.ftblibrary.math.XZ;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.world.level.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.DeflaterOutputStream;

/**
 * @author LatvianModder
 */
public class MapDimension implements MapTask {
	private static final Logger LOGGER = LogManager.getLogger();
	private static MapDimension current;

	@Nullable
	public static MapDimension getCurrent() {
		if (current == null) {
			if (MapManager.inst == null) {
				LOGGER.warn("Attempted to access MapManger before it's setup");
				return null;
			}
			ResourceKey<Level> levelDimension = Minecraft.getInstance().level.dimension();
			if(levelDimension != null)
				current = MapManager.inst.getDimension(levelDimension);
		}

		return current;
	}

	public static void updateCurrent() {
		current = null;
	}

	public final MapManager manager;
	public final ResourceKey<Level> dimension;
	public final String safeDimensionId;
	public final Path directory;
	private Map<XZ, MapRegion> regions;
	private List<Waypoint> waypoints;
	public boolean saveData;
	public Long2IntMap loadedChunkView;

	public MapDimension(MapManager m, ResourceKey<Level> id) {
		manager = m;
		dimension = id;
		safeDimensionId = dimension.location().toString().replace(':', '_');
		directory = manager.directory.resolve(safeDimensionId);
		saveData = false;
		loadedChunkView = new Long2IntOpenHashMap();
	}

	@Override
	public String toString() {
		return safeDimensionId;
	}

	public Collection<MapRegion> getLoadedRegions() {
		return regions == null ? Collections.emptyList() : regions.values();
	}

	public MapDimension created() {
		manager.saveData = true;
		return this;
	}

	public Map<XZ, MapRegion> getRegions() {
		synchronized (manager.lock) {
			if (regions == null) {
				regions = new HashMap<>();

				if (Files.notExists(directory)) {
					try {
						Files.createDirectories(directory);
					} catch (Exception ex) {
						throw new RuntimeException(ex);
					}
				}

				if (!MapIOUtils.read(directory.resolve("dimension.regions"), stream -> {
					stream.readByte();
					int version = stream.readByte();
					int s = stream.readShort();

					for (int i = 0; i < s; i++) {
						int x = stream.readByte();
						int z = stream.readByte();

						MapRegion c = new MapRegion(this, XZ.of(x, z));
						regions.put(c.pos, c);
					}
				})) {
					saveData = true;
				}
			}

			return regions;
		}
	}

	@Nullable
	public int[] getColors(int x, int z, ColorsFromRegion colors) {
		MapRegion region = getRegions().get(XZ.of(x, z));
		return region == null ? null : colors.getColors(region.getDataBlocking());
	}

	public MapRegion getRegion(XZ pos) {
		synchronized (manager.lock) {
			Map<XZ, MapRegion> map = getRegions();
			MapRegion region = map.get(pos);

			if (region == null) {
				region = new MapRegion(this, pos);
				region.created();
				map.put(pos, region);
			}

			return region;
		}
	}

	public List<Waypoint> getWaypoints() {
		if (waypoints == null) {
			waypoints = new ArrayList<>();

			Path file = directory.resolve("waypoints.json");

			if (Files.exists(file)) {
				try (Reader reader = Files.newBufferedReader(file)) {
					JsonObject json = FTBChunks.GSON.fromJson(reader, JsonObject.class);

					if (json.has("waypoints")) {
						for (JsonElement e : json.get("waypoints").getAsJsonArray()) {
							JsonObject o = e.getAsJsonObject();

							Waypoint w = new Waypoint(this);
							w.hidden = o.get("hidden").getAsBoolean();
							w.name = o.get("name").getAsString();
							w.x = o.get("x").getAsInt();
							w.y = o.get("y").getAsInt();
							w.z = o.get("z").getAsInt();
							w.color = 0xFFFFFF;

							if (o.has("color")) {
								try {
									w.color = Integer.decode(o.get("color").getAsString());
								} catch (Exception ex) {
								}
							}

							if (o.has("type")) {
								w.type = WaypointType.TYPES.getOrDefault(o.get("type").getAsString(), WaypointType.DEFAULT);
							}

							waypoints.add(w);
							w.update();
						}
					}
				} catch (Exception ex) {
					ex.printStackTrace();
				}
			}

			RefreshMinimapIconsEvent.trigger();
		}

		return waypoints;
	}

	public void release() {
		for (MapRegion region : getLoadedRegions()) {
			region.release();
		}

		regions = null;
		waypoints = null;
	}

	@Override
	public void runMapTask() throws Exception {
		List<Waypoint> waypoints = new ArrayList<>(getWaypoints());
		List<MapRegion> regionList = new ArrayList<>(getRegions().values());

		if (!waypoints.isEmpty() || !regionList.isEmpty()) {
			FTBChunks.EXECUTOR.execute(() -> {
				try {
					writeData(waypoints, regionList);
				} catch (Exception ex) {
					FTBChunks.LOGGER.error("Failed to write map dimension " + safeDimensionId + ":");
					ex.printStackTrace();
				}
			});
		}
	}

	private void writeData(List<Waypoint> waypoints, List<MapRegion> regionList) throws IOException {
		JsonObject json = new JsonObject();
		JsonArray waypointArray = new JsonArray();

		for (Waypoint w : waypoints) {
			JsonObject o = new JsonObject();
			o.addProperty("hidden", w.hidden);
			o.addProperty("name", w.name);
			o.addProperty("x", w.x);
			o.addProperty("y", w.y);
			o.addProperty("z", w.z);
			o.addProperty("color", String.format("#%06X", 0xFFFFFF & w.color));
			o.addProperty("type", w.type.id);
			waypointArray.add(o);
		}

		json.add("waypoints", waypointArray);

		try (Writer writer = Files.newBufferedWriter(directory.resolve("waypoints.json"))) {
			FTBChunks.GSON.toJson(json, writer);
		}

		ByteArrayOutputStream baos = new ByteArrayOutputStream();

		try (DataOutputStream stream = new DataOutputStream(new BufferedOutputStream(new DeflaterOutputStream(baos)))) {
			stream.writeByte(0);
			stream.writeByte(1);
			stream.writeShort(regionList.size());

			for (MapRegion region : regionList) {
				stream.writeByte(region.pos.x);
				stream.writeByte(region.pos.z);
			}
		}

		Files.write(directory.resolve("dimension.regions"), baos.toByteArray());
	}

	public void sync() {
		long now = System.currentTimeMillis();
		getRegions().values().stream().sorted(Comparator.comparingDouble(MapRegion::distToPlayer)).forEach(region -> FTBChunksClient.queue(new SyncTXTask(region, now)));
	}
}
