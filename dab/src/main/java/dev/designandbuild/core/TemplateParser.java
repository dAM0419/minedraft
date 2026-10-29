package dev.designandbuild.core;

import dev.designandbuild.DesignAndBuild;
import net.minecraft.SharedConstants;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 바닐라 구조물 .nbt 형식 읽기·쓰기.
 *
 * DaB 정보는 루트의 "designandbuild" 항목에 넣는다 (바닐라는 무시한다):
 * <pre>
 * designandbuild: { name: "...", mount: "down", tags: { source: [...], kind: [...], terrain: [...] } }
 * </pre>
 * 직소 블록은 지정된 최종 블록으로, 구조물 블록은 공기로 바꾸고, 구조물 공허는 건너뛴다.
 */
public final class TemplateParser {
    private TemplateParser() {}

    public static final String[] TAG_AXES = {"source", "kind", "terrain"};

    public static TemplateData parse(String id, String fallbackName, CompoundTag raw, List<String> autoTags) {
        CompoundTag tag = raw;
        int current = SharedConstants.getCurrentVersion().getDataVersion().getVersion();
        int version = NbtUtils.getDataVersion(tag, 500);
        if (version < current) {
            try {
                tag = DataFixTypes.STRUCTURE.updateToCurrentVersion(DataFixers.getDataFixer(), tag, version);
            } catch (Exception e) {
                DesignAndBuild.LOGGER.warn("Could not upgrade structure {}", id, e);
            }
        }

        ListTag sizeTag = tag.getList("size", Tag.TAG_INT);
        int sx = sizeTag.size() == 3 ? sizeTag.getInt(0) : 0;
        int sy = sizeTag.size() == 3 ? sizeTag.getInt(1) : 0;
        int sz = sizeTag.size() == 3 ? sizeTag.getInt(2) : 0;

        ListTag paletteTag;
        if (tag.contains("palettes", Tag.TAG_LIST)) {
            ListTag palettes = tag.getList("palettes", Tag.TAG_LIST);
            paletteTag = palettes.isEmpty() ? new ListTag() : palettes.getList(0);
        } else {
            paletteTag = tag.getList("palette", Tag.TAG_COMPOUND);
        }
        BlockState[] palette = new BlockState[paletteTag.size()];
        for (int i = 0; i < palette.length; i++) {
            palette[i] = NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(), paletteTag.getCompound(i));
        }

        ListTag blocks = tag.getList("blocks", Tag.TAG_COMPOUND);
        int n = blocks.size();
        int[] xs = new int[n], ys = new int[n], zs = new int[n];
        BlockState[] states = new BlockState[n];
        int count = 0;
        for (int i = 0; i < n; i++) {
            CompoundTag b = blocks.getCompound(i);
            ListTag pos = b.getList("pos", Tag.TAG_INT);
            int si = b.getInt("state");
            if (pos.size() != 3 || si < 0 || si >= palette.length) continue;
            BlockState state = palette[si];
            if (state.is(Blocks.STRUCTURE_VOID)) continue;
            if (state.is(Blocks.JIGSAW)) {
                state = jigsawFinal(b.getCompound("nbt"));
            } else if (state.is(Blocks.STRUCTURE_BLOCK)) {
                state = Blocks.AIR.defaultBlockState();
            }
            xs[count] = pos.getInt(0);
            ys[count] = pos.getInt(1);
            zs[count] = pos.getInt(2);
            states[count] = state;
            count++;
        }
        if (count < n) {
            xs = java.util.Arrays.copyOf(xs, count);
            ys = java.util.Arrays.copyOf(ys, count);
            zs = java.util.Arrays.copyOf(zs, count);
            states = java.util.Arrays.copyOf(states, count);
        }

        String name = fallbackName;
        Direction mount = Direction.DOWN;
        List<String> tags = new ArrayList<>(autoTags);
        if (tag.contains("designandbuild", Tag.TAG_COMPOUND)) {
            CompoundTag meta = tag.getCompound("designandbuild");
            if (!meta.getString("name").isBlank()) name = meta.getString("name");
            Direction d = Direction.byName(meta.getString("mount"));
            if (d != null) mount = d;
            CompoundTag t = meta.getCompound("tags");
            for (String axis : TAG_AXES) {
                ListTag list = t.getList(axis, Tag.TAG_STRING);
                for (int i = 0; i < list.size(); i++) {
                    String value = clean(list.getString(i));
                    String full = axis + ":" + value;
                    if (!value.isEmpty() && !tags.contains(full)) tags.add(full);
                }
            }
        }
        return new TemplateData(id, name, sx, sy, sz, mount, tags, xs, ys, zs, states);
    }

    private static BlockState jigsawFinal(CompoundTag nbt) {
        String s = nbt.getString("final_state");
        if (s.isEmpty()) return Blocks.AIR.defaultBlockState();
        try {
            return BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK.asLookup(), s, true).blockState();
        } catch (Exception e) {
            return Blocks.AIR.defaultBlockState();
        }
    }

    /** 태그 값 정리: 소문자, 공백은 밑줄. */
    public static String clean(String value) {
        return value.trim().toLowerCase(Locale.ROOT).replace(' ', '_').replace(':', '_');
    }

    /** 저장용 .nbt 를 만든다. 칸 좌표는 [0, size) 상대 좌표. */
    public static CompoundTag write(String name, Direction mount, Map<String, List<String>> tagsByAxis,
                                    int sizeX, int sizeY, int sizeZ, int[] xs, int[] ys, int[] zs, BlockState[] states) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("DataVersion", SharedConstants.getCurrentVersion().getDataVersion().getVersion());
        ListTag size = new ListTag();
        size.add(IntTag.valueOf(sizeX));
        size.add(IntTag.valueOf(sizeY));
        size.add(IntTag.valueOf(sizeZ));
        tag.put("size", size);

        Map<BlockState, Integer> ids = new LinkedHashMap<>();
        ListTag blocks = new ListTag();
        for (int i = 0; i < states.length; i++) {
            Integer idx = ids.get(states[i]);
            if (idx == null) {
                idx = ids.size();
                ids.put(states[i], idx);
            }
            CompoundTag b = new CompoundTag();
            ListTag pos = new ListTag();
            pos.add(IntTag.valueOf(xs[i]));
            pos.add(IntTag.valueOf(ys[i]));
            pos.add(IntTag.valueOf(zs[i]));
            b.put("pos", pos);
            b.putInt("state", idx);
            blocks.add(b);
        }
        ListTag palette = new ListTag();
        for (BlockState s : ids.keySet()) palette.add(NbtUtils.writeBlockState(s));
        tag.put("palette", palette);
        tag.put("blocks", blocks);
        tag.put("entities", new ListTag());

        CompoundTag meta = new CompoundTag();
        meta.putString("name", name);
        meta.putString("mount", mount.getSerializedName());
        CompoundTag tagsTag = new CompoundTag();
        for (String axis : TAG_AXES) {
            ListTag list = new ListTag();
            for (String v : tagsByAxis.getOrDefault(axis, List.of())) list.add(StringTag.valueOf(clean(v)));
            tagsTag.put(axis, list);
        }
        meta.put("tags", tagsTag);
        tag.put("designandbuild", meta);
        return tag;
    }

    // ------------------------------------------------------------------
    // 자동 태그 (바닐라·모드 경로)
    // ------------------------------------------------------------------

    private static final Map<String, String> TERRAIN = new LinkedHashMap<>();
    private static final Map<String, String> KIND = new LinkedHashMap<>();

    static {
        for (String t : new String[]{"plains", "desert", "savanna", "taiga", "snowy", "jungle", "swamp", "badlands",
                "ocean", "beach", "mountain", "nether", "end", "mushroom", "cherry", "forest", "warm", "cold",
                "frozen", "ice", "deep", "mesa", "meadow", "grove", "dark_forest", "birch", "mangrove"}) {
            TERRAIN.put(t, t);
        }
        TERRAIN.put("snow", "snowy");
        TERRAIN.put("mountains", "mountain");
        TERRAIN.put("deserts", "desert");
        KIND.put("houses", "house");
        KIND.put("streets", "street");
        KIND.put("town_centers", "town_center");
        KIND.put("villages", "village");
        KIND.put("decor", "decor");
        KIND.put("igloo", "igloo");
        KIND.put("shipwreck", "shipwreck");
        KIND.put("ruined_portal", "ruined_portal");
        KIND.put("fossil", "fossil");
        KIND.put("nether_fossils", "fossil");
        KIND.put("bastion", "bastion");
        KIND.put("ancient_city", "ancient_city");
        KIND.put("trial_chambers", "trial_chambers");
        KIND.put("trail_ruins", "trail_ruins");
        KIND.put("pillager_outpost", "outpost");
        KIND.put("woodland_mansion", "mansion");
        KIND.put("end_city", "end_city");
        KIND.put("underwater_ruin", "underwater_ruin");
        KIND.put("towers", "tower");
        KIND.put("tower", "tower");
        KIND.put("bridges", "bridge");
        KIND.put("walls", "wall");
        KIND.put("ruins", "ruins");
    }

    /** 리소스 경로("village/plains/houses/plains_small_house_1")에서 자동 태그를 만든다. */
    public static List<String> autoTags(String namespace, String path) {
        List<String> tags = new ArrayList<>();
        tags.add("source:" + ("minecraft".equals(namespace) ? "vanilla" : clean(namespace)));
        String[] seg = path.split("/");
        for (int i = 0; i < seg.length; i++) {
            boolean file = i == seg.length - 1;
            String s = seg[i].toLowerCase(Locale.ROOT);
            String terrain = TERRAIN.get(s);
            if (terrain != null) {
                add(tags, "terrain:" + terrain);
                continue;
            }
            for (String word : s.split("_")) {
                String t = TERRAIN.get(word);
                if (t != null) add(tags, "terrain:" + t);
            }
            if (!file) {
                add(tags, "kind:" + KIND.getOrDefault(s, s));
            } else if (seg.length == 1) {
                add(tags, "kind:" + s.replaceAll("_?\\d+$", ""));
            }
        }
        return tags;
    }

    private static void add(List<String> tags, String tag) {
        if (!tags.contains(tag)) tags.add(tag);
    }

    /** 경로 마지막 이름을 읽기 좋게. */
    public static String prettyName(String path) {
        String last = path.substring(path.lastIndexOf('/') + 1);
        if (last.endsWith(".nbt")) last = last.substring(0, last.length() - 4);
        return last.replace('_', ' ');
    }
}
