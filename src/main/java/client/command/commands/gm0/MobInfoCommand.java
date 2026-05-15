/*
    This file is part of the HeavenMS MapleStory Server, commands OdinMS-based
    Copyleft (L) 2016 - 2019 RonanLana

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as
    published by the Free Software Foundation version 3 as published by
    the Free Software Foundation. You may not use, modify or distribute
    this program under any other version of the GNU Affero General Public
    License.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/

/*
   @Author: Nadav


*/

package client.command.commands.gm0;

import client.Character;
import client.Client;
import client.command.Command;
import provider.Data;
import provider.DataDirectoryEntry;
import provider.DataFileEntry;
import provider.DataProvider;
import provider.DataProviderFactory;
import provider.DataTool;
import provider.wz.WZFiles;
import server.life.MonsterInformationProvider;
import tools.Pair;

import java.sql.Array;
import java.util.Iterator;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static constants.game.GameConstants.ELEMENTS;
import static constants.game.GameConstants.ELEMENT_EFFECTIVENESS;

/**
 * GM command for querying mob information and spawn locations.
 * 
 * This class caches all mob spawn data and detailed stats asynchronously at server startup.
 * The cache is populated in a background thread to avoid blocking server startup.
 * Commands that execute before caching completes will wait up to 5 seconds for the cache,
 * then proceed with partial or no data if the timeout expires.
 * 
 * Thread safety: Static maps use ConcurrentHashMap for thread-safe concurrent access
 * during cache population and command execution.
 */
public class MobInfoCommand extends Command {
    {
        setDescription("");
    }

    private final static MobInfoCommand instance = new MobInfoCommand();
    private final static DataProvider mobData = DataProviderFactory.getDataProvider(WZFiles.MOB);
    private final static MonsterInformationProvider monsterInfoProvider = MonsterInformationProvider.getInstance();
    
    // Maps mob ID -> (map ID -> spawn count). Populated during cacheMobInfo().
    private final static Map<String, Map<String, Integer>> mobSpawnData = new ConcurrentHashMap<>();
    
    // Maps mob ID -> stat map. Lazy-loaded during cacheMobInfo() and by execute().
    private final static Map<String, Map<String, String>> mobCachedInfo = new ConcurrentHashMap<>();

    private final static List<String> mobAttributes = Arrays.asList("level", "maxHP", "exp", "elemAttr");
    private final static int MAX_RESULT_SIZE = 10;
    
    // Async cache state: signals when cache population is complete.
    private static volatile boolean cacheInProgress = false;
    private static CountDownLatch cacheLatch;
    private static final Logger log = LoggerFactory.getLogger(MobInfoCommand.class);

    /**
     * Pads a mob ID with leading zeros to ensure it is at least 7 characters long.
     * This formatting is required to match WZ file naming conventions (e.g., "0001001.img").
     */
    private static String padMobId(String mobId) {
        while (mobId.length() < 7) {
            mobId = "0" + mobId;
        }
        return mobId;
    }
    
    /**
     * Starts the mob cache population in a background thread.
     * Safe to call multiple times; only starts once.
     */
    public static void startCacheAsync() {
        if (cacheInProgress) return;
        cacheInProgress = true;
        cacheLatch = new CountDownLatch(1);
        new Thread(() -> {
            final long start = System.currentTimeMillis();
            log.info("Starting mob cache async");
            try {
                cacheMobInfo();
                final long duration = System.currentTimeMillis() - start;
                log.info("Mob cache completed in {} ms", duration);
            } catch (Throwable t) {
                log.error("Mob cache failed", t);
            } finally {
                cacheLatch.countDown();
            }
        }, "MobInfoCacheThread").start();
    }
    
    /**
     * Waits for the cache to finish populating, with a timeout.
     * Non-blocking: does not hold locks or block handler threads indefinitely.
     * 
     * @param timeoutMs Maximum time to wait in milliseconds.
     * @throws InterruptedException if the wait is interrupted.
     */
    public static void waitForCache(long timeoutMs) throws InterruptedException {
        if (cacheLatch != null) {
            cacheLatch.await(timeoutMs, TimeUnit.MILLISECONDS);
        }
    }

    @Override
    public void execute(Client c, String[] params) {
        // If cache is not ready, wait briefly for it (max 5 seconds) without blocking the handler.
        if (mobSpawnData.isEmpty()){
            try {
                waitForCache(5000);  // Wait up to 5 seconds for cache to complete.
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                c.getPlayer().dropMessage(5, "Mob cache is still loading. Try again in a moment.");
                return;
            }
            // If cache is still empty after timeout, continue anyway with partial data.
            if (mobSpawnData.isEmpty()) {
                c.getPlayer().dropMessage(5, "Mob data not yet available. Please try again later.");
                return;
            }
        }
        Character player = c.getPlayer();
        if (params.length < 1) {
            player.dropMessage(5, "The correct usage is '@mobinfo <monster name>'");
            return;
        }
        String monsterName = player.getLastCommandMessage();
        String output = "";

        // Search monsters with exact match (case-sensitive)
        // Replace with the next line if you'd like to use an exact match for the search
        // Iterator<Pair<Integer, String>> listIterator = MapleMonsterInformationProvider.getMobsIDsFromName(monsterName, true).iterator();

        // Case-insensitive search
        List<Pair<Integer, String>> searchResults = MonsterInformationProvider.getMobsIDsFromName(monsterName);
        Iterator<Pair<Integer, String>> listIterator = searchResults.subList(0, Math.min(searchResults.size(), MAX_RESULT_SIZE)).iterator();
            while(listIterator.hasNext()) {
                Pair<Integer, String> data = listIterator.next();
                int mobId = data.getLeft();
                Map<String, String> mobStats;
                String idKey = padMobId("" + mobId);
                if (mobCachedInfo.containsKey(idKey)) {
                    mobStats = mobCachedInfo.get(idKey);
                } else {
                    mobStats = getMobStats(idKey, mobAttributes);
                    if (mobStats != null && mobStats.get("id") != null) {
                        mobCachedInfo.put(idKey, mobStats);
                    }
                }
                
                // Null check: mobStats may be null if mob data file doesn't exist or couldn't be loaded.
                if (mobStats == null) {
                    output += "#rMob data not found for ID " + idKey + ".\r\n";
                    continue;
                }
                
                output +="#F"+mobStats.get("img")+"#\r\n"+"#d#o"+mobStats.get("id")+"#";
                output += monsterInfoProvider.isBoss(mobId)? " (Boss)\r\n" : "\r\n";

                // Ugly implementation, but allows for adjustment of getMobStats()

                for (String key : mobStats.keySet()) {
                    String stat = mobStats.get(key);
                    if ( key.equals("img") || key.equals("id")) { continue; }
                    if (key.equals("EXP")) { stat = ""  + (Integer.valueOf(stat)*player.getExpRate()); }
                    output+="#r" + key+ ": "+stat +"\r\n";
                }
                output += "\r\n";

                if (!mobSpawnData.containsKey(mobStats.get("id"))){
                    output+="No spawn data found for this monster.\r\n\r\n";
                    continue;
                }
                output += "Spawn locations:\r\n";

                for (String mapId : mobSpawnData.get(mobStats.get("id")).keySet()){
                    output += "#e#b#m" + mapId + "##n#k\t(" + mobSpawnData.get(mobStats.get("id")).get(mapId) + " mobs)\r\n";
                }
            }
            output+= "\r\n\r\n";
            c.getAbstractPlayerInteraction().npcTalk(9010000, output);
    }

    /**
     * Populates the mob spawn data and mob stats caches by scanning all map files in the WZ data.
     * 
     * This method performs a two-pass approach:
     * 1. First pass: Scan all map life data to collect unique mob IDs and count spawns per map.
     * 2. Second pass: Load detailed stats (level, HP, exp, elements) for each unique mob.
     * 
     * This is an expensive operation (typically 50+ seconds) and should be called once at startup.
     * WARNING: Not thread-safe. Do not call while execute() is accessing the caches.
     */
    public static void cacheMobInfo() {
        DataProvider mapWz = DataProviderFactory.getDataProvider(WZFiles.MAP);
        Set<String> processedMobIds = new HashSet<>();

        // === PASS 1: Collect spawn data and unique mob IDs from all map files ===
        for (DataDirectoryEntry dir : mapWz.getRoot().getSubdirectories()){
            if (!dir.getName().equals("Map")) continue;
            
            for (DataDirectoryEntry mapDir : dir.getSubdirectories()){
                for (DataFileEntry file : mapDir.getFiles()){
                    for (Data mapData : mapWz.getData("Map/"+file.getParent().getName()+"/"+file.getName())){
                        if (!mapData.getName().equals("life")) continue;
                        
                        try {
                            String mapId = file.getName().replaceAll(".img", "");
                            Data lifeData = mapData.getChildByPath("../life");
                            if (lifeData == null) continue;
                            
                            for (Data life : lifeData.getChildren()){
                                Data idData = life.getChildByPath("id");
                                if (idData == null) continue;
                                
                                String mobId = DataTool.getString(idData);
                                if (mobId == null || mobId.isEmpty()) continue;
                                
                                mobId = padMobId(mobId);
                                processedMobIds.add(mobId);
                                
                                // Thread-safe spawn count update using ConcurrentHashMap.
                                mobSpawnData.computeIfAbsent(mobId, k -> new ConcurrentHashMap<>())
                                    .merge(mapId, 1, Integer::sum);
                            }
                        } catch (Exception e) {
                            continue;
                        }
                    }
                }
            }
        }
        // === PASS 2: Load detailed stats for all unique mob IDs ===
        for (String mobId : processedMobIds) {
            try {
                if (!mobCachedInfo.containsKey(mobId)) {
                    Map<String, String> stats = instance.getMobStats(mobId, mobAttributes);
                    if (stats != null && stats.get("id") != null) {
                        mobCachedInfo.put(mobId, stats);
                    }
                }
            } catch (Exception e) {
                // ignore caching errors for individual mobs
            }
        }
    }

    /**
     * Loads detailed mob stats from WZ data for a given mob ID.
     * 
     * @param mobId The padded mob ID (7+ characters, e.g., "0001001").
     * @param attributes List of stat attributes to extract (e.g., "level", "maxHP", "exp", "elemAttr").
     * @return A map of stat name -> stat value, or null if the mob data file doesn't exist.
     */
    public Map<String, String> getMobStats(String mobId, List<String> attributes) {
        Data mobData = MobInfoCommand.mobData.getData(mobId+".img");
        if (mobData == null) {
            // Mob data file not found; this is not an error—some mobs may not have WZ entries.
            return null;
        }
        
        Map<String, String> mobStats = new HashMap<String, String>();
        Data linkData = mobData.getChildByPath("info/link");
        String mobImgId = linkData != null ? DataTool.getString(linkData) : mobId;
        mobStats.put("id", mobId);
        mobStats.put("img", "Mob/"+mobImgId+".img/stand/0");
        for (String attr : attributes){
            // Note: Some attributes don't exist on all mobs (e.g., elemAttr, exp for certain mob types).
            Data attrData = mobData.getChildByPath("info/" + attr);
            if (attrData == null) {
                continue;  // Skip missing attributes gracefully.
            }
            
            try {
                if (attr.toLowerCase().equals("elemattr")) {
                    String value = DataTool.getString(attrData);
                    if (value == null || value.isEmpty()) continue;
                    
                    Map<String, String> parsedElements = parseElementalAttributes(value);
                    for (String effectivenessLevel : parsedElements.keySet()){
                        mobStats.put(effectivenessLevel+" to", parsedElements.get(effectivenessLevel));
                    }
                    continue;
                }
                int value = DataTool.getIntConvert(attrData);
                mobStats.put(attr.replaceAll("max", "").toUpperCase(), ""+value);
            } catch (Exception e) {
                // Silently skip attributes that cannot be parsed; mob will still display with partial data.
            }
        }
        return mobStats;
}

/**
 * Parses elemental attribute data from WZ format into human-readable categories.
 * 
 * Format: Two-character pairs where the first character is the element (I=Ice, F=Fire, etc.)
 * and the second is the effectiveness level (0=immune, 1=resistant, 2=weak).
 * 
 * @param value The raw elemAttr string from WZ data (e.g., "I0F2D1").
 * @return A map of effectiveness level -> comma-separated element list.
 */
private Map<String, String> parseElementalAttributes(String value) {
        if (value == null || value.isEmpty()) return new HashMap<>();
        if (value.length() % 2 != 0) throw new IllegalArgumentException("Invalid elemAttr value: "+value);
        Map<String, List<String>> groupedElements = new HashMap<>();

        groupedElements.put("Immune", new ArrayList<>());
        groupedElements.put("Resistant", new ArrayList<>());
        groupedElements.put("Weak", new ArrayList<>());

        for (int i =0 ; i < value.length(); i += 2 ){

            String elementKey = value.substring(i, i + 1);
            int level = java.lang.Character.getNumericValue(value.charAt(i+1));

            String element = ELEMENTS.get(elementKey);
            String effectiveness = ELEMENT_EFFECTIVENESS.get(level);

            if (effectiveness != null) {
                groupedElements.get(effectiveness).add(element);
            }

        }

        // Convert to a ready-to-print list
        Map<String, String> result = new HashMap<>();
        for (Map.Entry<String, List<String>> entry : groupedElements.entrySet()) {
            if (!entry.getValue().isEmpty()){
                result.put(entry.getKey(), String.join(", ", entry.getValue()));
            }
        }
        return result;
}

}
