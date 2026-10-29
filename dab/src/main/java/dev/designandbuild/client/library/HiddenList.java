package dev.designandbuild.client.library;

import dev.designandbuild.client.ClientConfig;
import dev.designandbuild.core.TemplateParser;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 라이브러리 숨김 목록 (이 클라이언트에만 적용). 한 줄이 한 항목이다:
 * <pre>
 * tag:source:yungsapi
 * structure:minecraft:village/plains/houses/plains_temple_3     (바닐라·모드 구조물)
 * structure:world:...                                            (월드 구조물)
 * structure:file:등록폴더기준/상대경로.nbt                         (내 폴더 구조물)
 * </pre>
 * 이 컴퓨터에 없는 구조물·태그도 목록에는 남긴다 (모드를 다시 넣거나 남의 목록을 받았을 때 바로 적용되도록).
 * 같은 줄 형식이 내보내기 파일 형식이기도 하다.
 */
public final class HiddenList {
    private HiddenList() {}

    public static final String TAG = "tag:", STRUCT = "structure:";
    private static long revision;

    public static long revision() {
        return revision;
    }

    public static Set<String> lines() {
        return ClientConfig.get().hidden;
    }

    /** 내보내기·불러오기 폴더. */
    public static Path folder() {
        return FMLPaths.CONFIGDIR.get().resolve("designandbuild").resolve("hidden");
    }

    private static void changed() {
        revision++;
        ClientConfig.save();
    }

    // ------------------------------------------------------------------
    // 판정
    // ------------------------------------------------------------------

    public static String structureLine(LibraryEntry e, List<Path> folders) {
        String id = e.id();
        if (id.startsWith("server:")) return STRUCT + id.substring("server:".length());
        if (id.startsWith("world:")) return STRUCT + id;
        Path f = e.file();
        if (f != null) {
            for (Path folder : folders) {
                if (f.startsWith(folder)) {
                    return STRUCT + "file:" + folder.relativize(f).toString().replace('\\', '/');
                }
            }
            return STRUCT + "file:" + f.toString().replace('\\', '/');
        }
        return STRUCT + id;
    }

    /** 구조물 자체를 숨겼거나, 숨긴 태그가 하나라도 붙어 있으면 숨김. */
    public static boolean isHidden(LibraryEntry e, Set<String> lines, List<Path> folders) {
        if (lines.isEmpty()) return false;
        if (lines.contains(structureLine(e, folders))) return true;
        for (String t : e.tags()) if (lines.contains(TAG + t)) return true;
        return false;
    }

    public static boolean isHidden(LibraryEntry e) {
        return isHidden(e, lines(), ClientConfig.allFolders());
    }

    // ------------------------------------------------------------------
    // 변경
    // ------------------------------------------------------------------

    public static void hideStructure(LibraryEntry e) {
        if (lines().add(structureLine(e, ClientConfig.allFolders()))) changed();
    }

    public static void hideTag(String tag) {
        if (lines().add(TAG + tag)) changed();
    }

    public static void unhide(String line) {
        if (lines().remove(line)) changed();
    }

    public static void clear() {
        if (!lines().isEmpty()) {
            lines().clear();
            changed();
        }
    }

    // ------------------------------------------------------------------
    // 내보내기 · 불러오기
    // ------------------------------------------------------------------

    /** 지금 숨김 목록을 폴더에 .txt 로 저장. 저장한 파일. */
    public static Path export() throws IOException {
        Path dir = folder();
        Files.createDirectories(dir);
        LocalDateTime now = LocalDateTime.now();
        Path file = dir.resolve("hidden-" + now.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".txt");
        List<String> out = new ArrayList<>();
        out.add("# DaB hidden list · " + now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        out.add("# tag:<axis>:<value>   structure:<id>   (# = comment)");
        List<String> tags = new ArrayList<>(), structs = new ArrayList<>();
        for (String l : lines()) (l.startsWith(TAG) ? tags : structs).add(l);
        tags.sort(String::compareTo);
        structs.sort(String::compareTo);
        out.addAll(tags);
        out.addAll(structs);
        Files.write(file, out, StandardCharsets.UTF_8);
        return file;
    }

    public record Listing(Path file, int count, long modified) {}

    /** 폴더 안 .txt 목록, 새로 만든 것부터. */
    public static List<Listing> listFiles() {
        List<Listing> out = new ArrayList<>();
        Path dir = folder();
        if (!Files.isDirectory(dir)) return out;
        try (Stream<Path> s = Files.list(dir)) {
            for (Path f : s.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".txt")).toList()) {
                try {
                    Parsed p = parse(f);
                    out.add(new Listing(f, p.valid().size(), Files.getLastModifiedTime(f).toMillis()));
                } catch (IOException ignored) {
                }
            }
        } catch (IOException ignored) {
        }
        out.sort((a, b) -> Long.compare(b.modified(), a.modified()));
        return out;
    }

    public record Parsed(List<String> valid, int skipped) {}

    public static Parsed parse(Path file) throws IOException {
        List<String> valid = new ArrayList<>();
        int skipped = 0;
        for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String line = raw.strip();
            if (!line.isEmpty() && line.charAt(0) == '﻿') line = line.substring(1).strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            if (validLine(line)) {
                if (!valid.contains(line)) valid.add(line);
            } else {
                skipped++;
            }
        }
        return new Parsed(valid, skipped);
    }

    private static boolean validLine(String line) {
        if (line.startsWith(TAG)) {
            String rest = line.substring(TAG.length());
            int c = rest.indexOf(':');
            if (c <= 0 || c == rest.length() - 1) return false;
            String axis = rest.substring(0, c);
            for (String a : TemplateParser.TAG_AXES) if (a.equals(axis)) return true;
            return false;
        }
        return line.startsWith(STRUCT) && line.length() > STRUCT.length();
    }

    /** 이 컴퓨터에 있는 구조물·태그의 줄 전부 (불러올 때 "없는 항목"을 세는 데 쓴다). */
    public static Set<String> knownLines(List<LibraryEntry> all) {
        Set<String> known = new HashSet<>();
        List<Path> folders = ClientConfig.allFolders();
        for (LibraryEntry e : all) {
            known.add(structureLine(e, folders));
            for (String t : e.tags()) known.add(TAG + t);
        }
        return known;
    }

    /** 불러온 줄을 적용한다. 돌려주는 값 = {적용한 줄 수, 이 컴퓨터에 없는 줄 수, 건너뛴 줄 수}. */
    public static int[] apply(Parsed p, boolean replace, List<LibraryEntry> all) {
        if (replace) lines().clear();
        lines().addAll(p.valid());
        Set<String> known = knownLines(all);
        int missing = 0;
        for (String l : p.valid()) if (!known.contains(l)) missing++;
        changed();
        return new int[]{p.valid().size(), missing, p.skipped()};
    }
}
