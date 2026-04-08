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

import java.util.Iterator;
import java.util.*;

public class MonsterInfoCommand extends Command {
    {
        setDescription("");
    }

    private final static DataProvider mobData = DataProviderFactory.getDataProvider(WZFiles.MOB);
    private final static MonsterInformationProvider monsterInfoProvider = MonsterInformationProvider.getInstance();
    private final static Map<String, Map<String, Integer>> mobSpawnData = new HashMap<String, Map<String, Integer>>();
    private final static int MAX_RESULT_SIZE = 10;
    private final static MonsterInfoCommand instance = new MonsterInfoCommand();

    @Override
    public void execute(Client c, String[] params) {
        if (mobSpawnData.isEmpty()){
            // if the spawn map wasn't cached on server start, it will be cached on the first command execution
            buildMobSpawnMap();
        }
        Character player = c.getPlayer();
        if (params.length < 1) {
            player.dropMessage(5, "The correct usage is '@monsterinfo <monster name>'");
            return;
        }
        String monsterName = player.getLastCommandMessage();
        String output = "";

        // Search monsters with exact match (case-sensitive)
        // Replace with the next line if you'd like to use an exact match for the search
        // Iterator<Pair<Integer, String>> listIterator = MapleMonsterInformationProvider.getMobsIDsFromName(monsterName, true).iterator();

        // Case-insensitive search
        Iterator<Pair<Integer, String>> listIterator = MonsterInformationProvider.getMobsIDsFromName(monsterName).subList(0, MAX_RESULT_SIZE).iterator();
            while(listIterator.hasNext()) {
                Pair<Integer, String> data = listIterator.next();
                int mobId = data.getLeft();
                Map<String, String> mobStats = getMobStats(""+mobId);
                output +="#F"+mobStats.get("img")+"#\r\n"+"#d#o"+mobStats.get("id")+"#";
                output += monsterInfoProvider.isBoss(mobId)? " (Boss)\r\n" : "\r\n";

                // Ugly implementation, but allows for adjustment of getMobStats()

                for (String key : mobStats.keySet()) {
                    int stat = Integer.valueOf(mobStats.get(key));
                    if ( key.equals("img") || key.equals("id")) { continue; }
                    if (key.equals("EXP")) { stat*=player.getExpRate(); }
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

    public static void buildMobSpawnMap() {
        DataProvider mapWz = DataProviderFactory.getDataProvider(WZFiles.MAP);

        for (DataDirectoryEntry dir : mapWz.getRoot().getSubdirectories()){
        if (dir.getName().equals("Map")){
            for (DataDirectoryEntry mapDir : dir.getSubdirectories()){
                for (DataFileEntry file : mapDir.getFiles()){
                    for (Data mapData : mapWz.getData("Map/"+file.getParent().getName()+"/"+file.getName())){
                        if(!mapData.getName().equals("life")) { continue; }
                        try {
                            String mapId = file.getName().replaceAll(".img", "");
                            for (Data life : mapData.getChildByPath("../life").getChildren()){
                                String mobId = DataTool.getString(life.getChildByPath("id"));
                                if(mobSpawnData.containsKey(mobId)){
                                    if(!mobSpawnData.get(mobId).containsKey(mapId)){
                                        mobSpawnData.get(mobId).put(mapId, 1);
                                    } else {
                                        mobSpawnData.get(mobId).put(mapId, mobSpawnData.get(mobId).get(mapId)+1);
                                    }
                                } else {
                                    Map<String, Integer> tempMap = new HashMap<String, Integer>();
                                    tempMap.put(mapId, 1);
                                    mobSpawnData.put(mobId, tempMap);
                                }
                            }
                        } catch (Exception e) {
                            continue;
                        }
                    }
                }
            }
        }
    }
    }

public Map<String, String> getMobStats(String mobId) {
        while (mobId.length() < 7){
            mobId = "0" + mobId;
        }
        Data mobData = MonsterInfoCommand.mobData.getData(mobId+".img");
        Map<String, String> mobStats = new HashMap<String, String>();
        String mobImgId = mobData.getChildByPath("info/link") != null ? DataTool.getString(mobData.getChildByPath("info/link")) : mobId;
        mobStats.put("id", mobId);
        mobStats.put("HP", ""+DataTool.getInt(mobData.getChildByPath("info/maxHP")));
        mobStats.put("EXP", ""+DataTool.getInt(mobData.getChildByPath("info/exp")));
        mobStats.put("img", "Mob/"+mobImgId+".img/stand/0");
        return mobStats;

}

}
