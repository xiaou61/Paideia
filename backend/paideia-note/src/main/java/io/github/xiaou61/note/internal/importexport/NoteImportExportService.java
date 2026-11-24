package io.github.xiaou61.note.internal.importexport;

import io.github.xiaou61.note.internal.book.NoteBookService;
import io.github.xiaou61.note.internal.note.NoteService;
import io.github.xiaou61.platform.BizException;
import io.github.xiaou61.platform.ErrorCode;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 笔记的 .md 导入导出（WORK-087 TASK-006）。
 *
 * <p>导出：单篇给正文 Markdown；整库打成 zip（目录=文件夹，文档=标题.md）。
 * 导入：接受 zip 或单篇 Markdown，按包内相对路径还原成目录树。
 *
 * <p>安全（这是信任边界，用户上传的字节）：拒绝路径穿越（{@code ..}、绝对路径、反斜杠）、
 * 限制条目数（500）与解压总量（20MB）与单文件大小，超限抛业务异常而不是把内存吃光。
 */
@Service
public class NoteImportExportService {

    private static final int MAX_ENTRIES = 500;
    private static final long MAX_TOTAL_BYTES = 20L * 1024 * 1024;
    private static final int MAX_FILE_BYTES = 1_000_000;
    private static final int MAX_NAME_CHARS = 200;

    private final NoteService notes;
    private final NoteBookService books;

    NoteImportExportService(NoteService notes, NoteBookService books) {
        this.notes = notes;
        this.books = books;
    }

    /** 导入结果：新建了多少目录与文档。 */
    public record ImportResult(int folders, int documents) {
    }

    // ---------------------------------------------------------------- 导出

    /** 单篇导出为 Markdown 正文。 */
    public String exportDoc(long userId, long noteId) {
        NoteService.NoteView note = notes.read(userId, noteId);
        String title = note.title() == null ? "笔记" : note.title();
        String body = note.body() == null ? "" : note.body();
        return "# " + title + "\n\n" + body + "\n";
    }

    /** 整库导出为 zip 字节。 */
    public byte[] exportBook(long userId, long bookId) {
        books.requireOwned(userId, bookId);
        List<NoteService.NoteNode> nodes = notes.tree(userId, bookId);
        Map<Long, List<NoteService.NoteNode>> children = new HashMap<>();
        for (NoteService.NoteNode node : nodes) {
            children.computeIfAbsent(node.parentId(), k -> new ArrayList<>()).add(node);
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buffer, StandardCharsets.UTF_8)) {
            writeChildren(userId, children, null, "", zip);
        } catch (IOException ex) {
            throw new BizException(ErrorCode.INTERNAL, "导出失败");
        }
        return buffer.toByteArray();
    }

    private void writeChildren(long userId, Map<Long, List<NoteService.NoteNode>> children, Long parentId,
                               String prefix, ZipOutputStream zip) throws IOException {
        List<NoteService.NoteNode> siblings = children.get(parentId);
        if (siblings == null) {
            return;
        }
        Map<String, Integer> used = new HashMap<>();
        for (NoteService.NoteNode node : siblings) {
            String name = sanitize(node.title());
            if ("folder".equals(node.kind())) {
                String path = prefix + unique(used, name, "") + "/";
                zip.putNextEntry(new ZipEntry(path));
                zip.closeEntry();
                writeChildren(userId, children, node.id(), path, zip);
            } else {
                String path = prefix + unique(used, name, ".md");
                zip.putNextEntry(new ZipEntry(path));
                String body = notes.read(userId, node.id()).body();
                zip.write((body == null ? "" : body).getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
    }

    // ---------------------------------------------------------------- 导入

    /** 导入一个 zip（含多级 .md）。全部在一个事务里，任一失败回滚。 */
    @Transactional
    public ImportResult importZip(long userId, long bookId, byte[] zipBytes) {
        books.requireOwned(userId, bookId);
        List<NoteService.NoteNode> existing = notes.tree(userId, bookId);
        Map<String, Long> folderIds = indexFolders(existing);
        int docs = 0;
        int entries = 0;
        long totalBytes = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipBytes), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > MAX_ENTRIES) {
                    throw invalid("压缩包条目过多（上限 " + MAX_ENTRIES + "）");
                }
                List<String> segments = safeSegments(entry.getName());
                if (segments.isEmpty()) {
                    continue;
                }
                boolean directory = entry.isDirectory() || entry.getName().endsWith("/");
                if (directory) {
                    ensureFolders(userId, bookId, segments, folderIds);
                    continue;
                }
                String last = segments.get(segments.size() - 1);
                if (!last.toLowerCase().endsWith(".md")) {
                    continue; // 只导 .md，忽略其它文件
                }
                byte[] content = readLimited(zip, MAX_FILE_BYTES);
                totalBytes += content.length;
                if (totalBytes > MAX_TOTAL_BYTES) {
                    throw invalid("解压总量超限（上限 20MB）");
                }
                Long parentFolder = ensureFolders(userId, bookId, segments.subList(0, segments.size() - 1), folderIds);
                String title = truncate(stripMd(last));
                notes.createNode(userId, bookId, parentFolder, "doc", title == null ? "未命名" : title,
                        new String(content, StandardCharsets.UTF_8));
                docs++;
            }
        } catch (IOException ex) {
            throw invalid("压缩包无法解析");
        }
        // folderIds 只含新增与已有，用差值不好算；这里用一次树重新统计更实
        int folderCount = (int) notes.tree(userId, bookId).stream().filter(n -> "folder".equals(n.kind())).count();
        int existingFolders = (int) existing.stream().filter(n -> "folder".equals(n.kind())).count();
        return new ImportResult(Math.max(0, folderCount - existingFolders), docs);
    }

    /** 导入单篇 Markdown 到库根（title 可空，用「导入文档」）。 */
    @Transactional
    public ImportResult importMarkdown(long userId, long bookId, String title, String markdown) {
        books.requireOwned(userId, bookId);
        String docTitle = truncate(title == null || title.isBlank() ? "导入文档" : title.trim());
        notes.createNode(userId, bookId, null, "doc", docTitle, markdown == null ? "" : markdown);
        return new ImportResult(0, 1);
    }

    // ---------------------------------------------------------------- 内部工具

    /** 已有节点按「路径」建索引，便于重复导入时复用同名目录。 */
    private static Map<String, Long> indexFolders(List<NoteService.NoteNode> nodes) {
        Map<Long, List<NoteService.NoteNode>> children = new HashMap<>();
        for (NoteService.NoteNode n : nodes) {
            children.computeIfAbsent(n.parentId(), k -> new ArrayList<>()).add(n);
        }
        Map<String, Long> index = new LinkedHashMap<>();
        walkFolders(children, null, "", index);
        return index;
    }

    private static void walkFolders(Map<Long, List<NoteService.NoteNode>> children, Long parentId,
                                    String prefix, Map<String, Long> index) {
        List<NoteService.NoteNode> siblings = children.get(parentId);
        if (siblings == null) {
            return;
        }
        for (NoteService.NoteNode n : siblings) {
            if ("folder".equals(n.kind())) {
                String path = prefix + n.title();
                index.putIfAbsent(path, n.id());
                walkFolders(children, n.id(), path + "/", index);
            }
        }
    }

    /** 确保 segments 描述的目录链存在，返回最深层目录 id（空则库根 null）。 */
    private Long ensureFolders(long userId, long bookId, List<String> segments, Map<String, Long> folderIds) {
        Long parent = null;
        StringBuilder path = new StringBuilder();
        for (String segment : segments) {
            if (path.length() > 0) {
                path.append('/');
            }
            path.append(segment);
            String key = path.toString();
            Long id = folderIds.get(key);
            if (id == null) {
                id = notes.createFolder(userId, bookId, parent, truncate(segment)).id();
                folderIds.put(key, id);
            }
            parent = id;
        }
        return parent;
    }

    private static List<String> safeSegments(String rawName) {
        if (rawName == null || rawName.isBlank()) {
            return List.of();
        }
        String unified = rawName.replace('\\', '/');
        if (unified.startsWith("/") || unified.matches("^[A-Za-z]:.*")) {
            throw invalid("压缩包包含非法绝对路径：" + rawName);
        }
        List<String> out = new ArrayList<>();
        for (String segment : unified.split("/")) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                if ("..".equals(segment)) {
                    throw invalid("压缩包包含路径穿越：../");
                }
                continue;
            }
            out.add(segment);
        }
        return out;
    }

    private static byte[] readLimited(ZipInputStream zip, int limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;
        while ((read = zip.read(chunk)) != -1) {
            if (out.size() + read > limit) {
                throw invalid("单个文件超过上限（" + limit + " 字节）");
            }
            out.write(chunk, 0, read);
        }
        return out.toByteArray();
    }

    private static String stripMd(String name) {
        return name.toLowerCase().endsWith(".md") ? name.substring(0, name.length() - 3) : name;
    }

    private static String sanitize(String title) {
        String raw = title == null || title.isBlank() ? "未命名" : title.trim();
        return truncate(raw.replaceAll("[\\\\/:*?\"<>|]", "_"));
    }

    private static String unique(Map<String, Integer> used, String name, String suffix) {
        int count = used.merge(name, 1, Integer::sum);
        return count == 1 ? name + suffix : name + "-" + count + suffix;
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= MAX_NAME_CHARS ? value : value.substring(0, MAX_NAME_CHARS);
    }

    private static BizException invalid(String message) {
        return new BizException(ErrorCode.INVALID_ARGUMENT, message);
    }
}
