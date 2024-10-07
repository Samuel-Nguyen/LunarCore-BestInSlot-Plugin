package dev.draven.builder;

import java.io.FileReader;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import dev.draven.builder.data.CharacterBuildData;
import dev.draven.builder.data.CharacterBuildData.BuildDetail;
import dev.draven.builder.data.CharacterBuildData.EquipmentData;
import dev.draven.builder.data.CharacterBuildData.RelicData;
import emu.lunarcore.LunarCore;
import emu.lunarcore.command.Command;
import emu.lunarcore.command.CommandArgs;
import emu.lunarcore.command.CommandHandler;
import emu.lunarcore.data.GameData;
import emu.lunarcore.data.excel.ItemExcel;
import emu.lunarcore.game.avatar.GameAvatar;
import emu.lunarcore.game.inventory.GameItem;
import emu.lunarcore.game.inventory.GameItemSubAffix;
import emu.lunarcore.game.inventory.Inventory;
import emu.lunarcore.game.inventory.tabs.InventoryTabType;
import emu.lunarcore.game.player.Player;
import emu.lunarcore.util.JsonUtils;
import emu.lunarcore.util.Utils;
import it.unimi.dsi.fastutil.ints.Int2IntMap;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;

@Command(
    label = "build",
    aliases = { "b" },
    permission = "player.give",
    requireTarget = true,
    desc = "/build [all/nickname/id] (build name) (-max)"
)
public class BuilderCommand implements CommandHandler {

    private static final int MAX_LEVEL = 80;
    private static final int MAX_PROMOTION = 6;
    private static final int MAX_RELIC_LEVEL = 15;
    private static final int EMPTY_EXP = 0;
    private static final int NO_REWARDS = 0b00101010;
    private CommandArgs args;
    private String specificName;

    @Override
    public void execute(CommandArgs args) {
        this.args = args;
        if (!hasInventorySpace()) {
            args.sendMessage("Error: The targeted player does not have enough space in their inventory.");
            return;
        }

        String message = parseBuildData();
        args.getSender().sendMessage(message);
    }

    private boolean hasInventorySpace() {
        Inventory inventory = args.getTarget().getInventory();
        return hasAvailableCapacity(inventory, InventoryTabType.RELIC) &&
                hasAvailableCapacity(inventory, InventoryTabType.EQUIPMENT);
    }

    private boolean hasAvailableCapacity(Inventory inventory, InventoryTabType type) {
        return inventory.getTab(type).getAvailableCapacity() > 0;
    }

    private String parseBuildData() {
        String input = args.get(0).toLowerCase();
        String buildName = args.get(1).isEmpty() ? "normal" : args.get(1).toLowerCase();
        List<CharacterBuildData> buildInformation = loadBuildInformation();

        if (buildInformation.isEmpty()) {
            return "No builds found. Please define one.";
        }

        return switch (input) {
            case "all", "a" -> processAllBuilds(buildInformation, buildName);
            default -> processSpecificBuild(buildInformation, input, buildName);
        };
    }

    private List<CharacterBuildData> loadBuildInformation() {
        try (FileReader fileReader = new FileReader(LunarCore.getConfig().getDataDir() + "/BuildDetails.json")) {
            return JsonUtils.loadToList(fileReader, CharacterBuildData.class);
        } catch (Exception e) {
            LunarCore.getLogger().error("Error loading BuildDetails.json", e);
            return Collections.emptyList();
        }
    }

    private String processAllBuilds(List<CharacterBuildData> buildInformation, String buildName) {
        long total = buildInformation.stream()
                .filter(buildInfo -> generateBuild(buildInfo, buildName))
                .count();

        return "Gave " + total + " characters relics for " + buildName.toUpperCase() + " build.";
    }

    private String processSpecificBuild(List<CharacterBuildData> buildInformation, String input,
            String buildName) {
        Optional<CharacterBuildData> buildInfoOpt = findBuild(buildInformation, input);
        return buildInfoOpt.map(buildInfo -> {
            generateBuild(buildInfo, buildName);
            return "Gave " + buildInfo.getFullName() + " relics for " + specificName.toUpperCase() + " build.";
        }).orElse("Build not found for input: " + input);
    }

    private Optional<CharacterBuildData> findBuild(List<CharacterBuildData> buildInformation, String input) {
        return isNumeric(input)
            ? buildInformation.stream().filter(b -> b.getAvatarId() == Utils.parseSafeInt(input)).findFirst()
            : buildInformation.stream().filter(b -> b.getAvatarName().equalsIgnoreCase(input)).findFirst();
    }

    public Boolean generateBuild(CharacterBuildData buildInfo, String buildName) {
        Player player = args.getTarget();
        GameAvatar avatar = getOrCreateAvatar(buildInfo.getAvatarId(), player);
        if (avatar == null) return false;

        setupAvatar(avatar, buildInfo);
        applyBuild(avatar, buildInfo, buildName);
        avatar.save();

        return true;
    }

    private GameAvatar getOrCreateAvatar(int id, Player player) {
        GameAvatar avatar = player.getAvatarById(id);
        if (avatar != null) {
            unequipAvatarItems(avatar, player);
            return avatar;
        }

        var excel = GameData.getAvatarExcelMap().get(id);
        if (excel == null) {
            LunarCore.getLogger().error("Avatar Excel data not found for ID: " + id);
            return null;
        }

        avatar = new GameAvatar(excel);
        player.addAvatar(avatar);
        return avatar;
    }

    @Deprecated
    private void unequipAvatarItems(GameAvatar avatar, Player player) {
        List<GameItem> unequipList = avatar.getEquips().keySet().stream()
                .map(avatar::unequipItem)
                .filter(item -> item != null)
                .toList();

        player.getInventory().removeItems(unequipList);
    }

    private void setupAvatar(GameAvatar avatar, CharacterBuildData buildInfo) {
        avatar.setLevel(MAX_LEVEL);
        avatar.setExp(EMPTY_EXP);
        avatar.setPromotion(MAX_PROMOTION);
        avatar.setRewards(NO_REWARDS);

        for (int pointId : avatar.getExcel().getSkillTreeIds()) {
            var skillTree = GameData.getAvatarSkillTreeExcel(pointId, 1);
            if (skillTree != null) {
                int pointLevel = Math.min(buildInfo.getSkillLevel(), skillTree.getMaxLevel());
                pointLevel = Math.max(pointLevel, skillTree.isDefaultUnlock() ? 1 : 0);
                avatar.getSkills().put(pointId, pointLevel);
            }
        }
    }

    private void applyBuild(GameAvatar avatar, CharacterBuildData buildInfo, String buildName) {
        BuildDetail buildDetail = getBuildDetail(buildInfo, buildName);
        avatar.setRank(buildDetail.getEidolonLevel());
        equipItem(avatar, buildDetail.getEquipment());
        equipRelics(avatar, buildDetail.getRelics());
        this.specificName = buildDetail.getBuildName();
    }

    private BuildDetail getBuildDetail(CharacterBuildData buildInfo, String buildName) {
        Optional<BuildDetail> buildDetailOpt = buildInfo.getBuilds().stream()
                .filter(detail -> detail.getBuildName().equalsIgnoreCase(buildName))
                .findFirst();

        if (buildDetailOpt.isEmpty()) {
            args.sendMessage("Warning: Build '" + buildName + "' not found for character " + buildInfo.getFullName() + ". Applying the first build instead.");
        }

        return buildDetailOpt.orElse(buildInfo.getBuilds().get(0)); // Returning the first build if not found
    }
    

    private void equipItem(GameAvatar avatar, EquipmentData equipmentData) {
        if (equipmentData != null) {
            var excel = GameData.getItemExcelMap().get(equipmentData.getItemId());
            if (excel != null) {
                GameItem equipment = createGameItem(excel, equipmentData.getEnhancementLevel());
                avatar.getOwner().getInventory().addItem(equipment);
                avatar.equipItem(equipment);
            }
        }
    }

    private GameItem createGameItem(ItemExcel excel, int enhancementLevel) {
        GameItem equipment = new GameItem(excel);
        equipment.setLevel(MAX_LEVEL);
        equipment.setExp(EMPTY_EXP);
        equipment.setPromotion(MAX_PROMOTION);
        equipment.setRank(enhancementLevel);
        return equipment;
    }

    private void equipRelics(GameAvatar avatar, List<RelicData> relicList) {
        Player player = avatar.getOwner();
        relicList.forEach(relicData -> {
            var excel = GameData.getItemExcelMap().get(relicData.getItemId());
            if (excel != null) {
                GameItem relic = new GameItem(excel);
                setupRelic(relic, relicData);
                player.getInventory().addItem(relic);
                avatar.equipItem(relic);
            }
        });
    }

    private void setupRelic(GameItem relic, RelicData relicData) {
        relic.setLevel(MAX_RELIC_LEVEL);
        relic.setExp(EMPTY_EXP);
        relic.setMainAffix(Optional.ofNullable(relicData.getPrimaryAffixId()).orElse(1));
        relic.resetSubAffixes();

        Int2IntMap subAffixMap = parseSubAffixes(relicData.getSubAffixes());
        applySubAffixes(relic, subAffixMap);

        if (args.hasFlag("-max")) {
            applyPerfectSubAffixes(relic);
        }
    }

    private Int2IntMap parseSubAffixes(String subAffixData) {
        Int2IntMap subAffixMap = new Int2IntOpenHashMap();
        Arrays.stream(subAffixData.split(" "))
                .map(s -> s.split("[:,]"))
                .filter(split -> split.length >= 2)
                .forEach(split -> subAffixMap.put(Integer.parseInt(split[0]), Integer.parseInt(split[1])));
        return subAffixMap;
    }

    private void applySubAffixes(GameItem relic, Int2IntMap subAffixMap) {
        subAffixMap.forEach((affixId, count) -> {
            if (count > 0) {
                var subAffix = GameData.getRelicSubAffixExcel(relic.getExcel().getRelicExcel().getSubAffixGroup(),
                        affixId);
                if (subAffix != null) {
                    relic.getSubAffixes().add(new GameItemSubAffix(subAffix, Math.min(count, 6)));
                }
            }
        });

        int upgrades = relic.getMaxNormalSubAffixCount() - relic.getCurrentSubAffixCount();
        if (upgrades > 0) {
            relic.addSubAffixes(upgrades);
        }
    }

    private void applyPerfectSubAffixes(GameItem relic) {
        if (relic.getSubAffixes() == null) {
            relic.resetSubAffixes();
        }

        relic.getSubAffixes().forEach(subAffix -> subAffix.setStep(subAffix.getCount() * 2));
    }

    private static boolean isNumeric(String str) {
        return str != null && str.matches("\\d+");
    }
}
