package io.github.xiaou61.ai.internal.resume;

import io.github.xiaou61.platform.BizException;
import io.github.xiaou61.platform.ErrorCode;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * 简历文件的**文本抽取**（WORK-018 TASK-002）。
 *
 * <p><b>它只做一件事：把文件变成纯文本。</b> 不理解"这是哪一段经历"，那是模型的事；也不做 OCR，
 * 那是本项明确的非目标。分界线清楚的好处是这里可以完全用夹具测——不需要任何模型调用。
 *
 * <p>四条安全与稳健纪律：
 * <ul>
 *   <li><b>大小与页数都封顶</b>：PDF 只读前 {@value #MAX_PAGES} 页，超出部分明确标记
 *       {@code truncated}，绝不静默截断。</li>
 *   <li><b>DOCX 走 JDK 自带 zip + XML</b>，不用 POI：只要段落文本的话，POI 带来的是整个
 *       ooxml-schemas（几十 MB）与一堆我们不用的 API。但必须自己关掉 DTD 与外部实体——
 *       否则一个带 {@code <!DOCTYPE>} 的 docx 就能让解析器去读本机文件（XXE）。</li>
 *   <li><b>zip 炸弹封顶</b>：解压总量超过 {@value #MAX_UNPACKED_BYTES} 字节直接拒绝，
 *       不靠"读完再说"（那时内存已经没了）。</li>
 *   <li><b>拒绝时给可读理由</b>：用户需要知道是"格式不支持"还是"没有文字层"——
 *       前者要换格式，后者要 OCR，而本项不做 OCR。两者混成一句"解析失败"等于没说。</li>
 * </ul>
 *
 * <p>加密 PDF 按"需要用户提供无密码版本"处理，不尝试空口令爆破。
 */
@Component
public class ResumeTextExtractor {

    /**
     * 最多读多少页。简历超过 20 页基本是附件合集，读下去只有成本没有收益。
     *
     * <p>ponytail: 硬上限 20 页；天花板是"20 页以后的经历不会被识别"。升级路径是让调用方
     * 按套餐给不同上限——但那需要先有真实的超长简历样本证明这不够。
     */
    static final int MAX_PAGES = 20;

    /** DOCX 解压总量上限，防 zip 炸弹。 */
    static final long MAX_UNPACKED_BYTES = 16L * 1024 * 1024;

    /** 小于这个字符数就当作"没有可读文字"——图片型 PDF 抽出来往往只有几个零散符号。 */
    static final int MIN_MEANINGFUL_CHARS = 50;

    /**
     * 抽取结果。{@code pages} 为 PDF 页数；DOCX 恒为 0（它没有稳定的页概念）。
     *
     * <p>public 是因为 WORK-076 的「文档导入」也要用它——这里其实是**通用文档文本抽取器**，
     * 名字带 resume 是历史来源（WORK-018 首次引入）。
     */
    public record ExtractedText(String text, boolean truncated, int pages) {
    }

    /**
     * 把文件字节抽成纯文本。**WORK-076 的知识库文档导入复用此方法**（故为 public）。
     *
     * @throws io.github.xiaou61.platform.BizException 空文件、非 PDF/docx、无文字层、加密、损坏时
     */
    public ExtractedText extract(byte[] content, String filename) {
        if (content == null || content.length == 0) {
            throw invalid("简历文件是空的");
        }
        String extension = extensionOf(filename);
        ExtractedText result;
        if (isPdf(content, extension)) {
            result = extractPdf(content);
        } else if (isDocx(content, extension)) {
            result = extractDocx(content);
        } else if ("doc".equals(extension)) {
            // 老版 .doc 是二进制 OLE 格式，JDK 没有稳妥的读法；引入 POI HWPF 只为读这一种
            // 已经很少见的格式，代价与收益不成立。让用户另存为 .docx 或 PDF 更省事。
            throw invalid("不支持旧版 .doc；请另存为 .docx 或 PDF 后再上传");
        } else {
            throw invalid("只支持 PDF 与文本型 .docx；当前文件既不是 PDF 也不是 docx");
        }
        if (countMeaningful(result.text()) < MIN_MEANINGFUL_CHARS) {
            throw invalid("这个文件里没有可读的文字（可能是扫描件或图片型 PDF）。"
                    + "本版不做 OCR，请上传电子版简历");
        }
        return result;
    }

    // ── PDF ─────────────────────────────────────────────────────────

    private ExtractedText extractPdf(byte[] content) {
        try (PDDocument document = Loader.loadPDF(content)) {
            int pages = document.getNumberOfPages();
            boolean truncated = pages > MAX_PAGES;
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setStartPage(1);
            stripper.setEndPage(Math.min(pages, MAX_PAGES));
            // sortByPosition：双栏简历里若不排序，抽取顺序会按内容流来，两栏会交错。
            // 它不能修好所有版式（复杂表格仍可能错位），但比默认值好，且没有额外的解析成本。
            stripper.setSortByPosition(true);
            return new ExtractedText(clean(stripper.getText(document)), truncated, pages);
        } catch (InvalidPasswordException exception) {
            throw invalid("这个 PDF 有密码保护；请提供无密码的版本");
        } catch (IOException exception) {
            throw invalid("这个 PDF 读不出来（文件可能损坏）");
        }
    }

    // ── DOCX ────────────────────────────────────────────────────────

    private ExtractedText extractDocx(byte[] content) {
        byte[] documentXml = readDocumentXml(content);
        String text = parseWordDocument(documentXml);
        // docx 没有可靠的分页信息（分页由排版引擎决定），所以 pages 恒为 0。
        return new ExtractedText(clean(text), false, 0);
    }

    /**
     * 从 docx 里只取出 {@code word/document.xml}，并在解压过程中累计总字节数。
     *
     * <p>解压上限在这里逐块累计而不是先解完再检查：zip 炸弹的全部杀伤力就在"先解完"那一刻。
     */
    private byte[] readDocumentXml(byte[] content) {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(content))) {
            long total = 0;
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                int read;
                while ((read = zip.read(chunk)) > 0) {
                    total += read;
                    if (total > MAX_UNPACKED_BYTES) {
                        throw invalid("这个 docx 解压后超过 "
                                + (MAX_UNPACKED_BYTES / 1024 / 1024) + " MB，已拒绝处理");
                    }
                    if ("word/document.xml".equals(entry.getName())) {
                        buffer.write(chunk, 0, read);
                    }
                }
            }
            if (buffer.size() == 0) {
                throw invalid("这个 docx 里找不到正文（word/document.xml）");
            }
            return buffer.toByteArray();
        } catch (IOException exception) {
            throw invalid("这个 docx 读不出来（文件可能损坏）");
        }
    }

    private String parseWordDocument(byte[] xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            // 三条都是必要项，不是"加固可选项"：docx 是用户上传的，DOCTYPE 可以让解析器
            // 去读 file:///... 并把内容带进异常信息里。
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setNamespaceAware(true);

            DocumentBuilder builder = factory.newDocumentBuilder();
            Document document = builder.parse(new ByteArrayInputStream(xml));

            List<String> paragraphs = new ArrayList<>();
            NodeList nodes = document.getElementsByTagNameNS(
                    "http://schemas.openxmlformats.org/wordprocessingml/2006/main", "p");
            for (int index = 0; index < nodes.getLength(); index++) {
                paragraphs.add(textOf(nodes.item(index)));
            }
            return String.join("\n", paragraphs);
        } catch (ParserConfigurationException | SAXException | IOException exception) {
            // SAXException 在 doctype 被禁时就落在这一支——对用户只说"格式不对"，
            // 不把解析器的原始报文回显出去（那里面可能有路径）
            throw invalid("这个 docx 的结构读不出来（可能不是有效的 Word 文档）");
        }
    }

    /** 取一个 {@code w:p} 里的全部 {@code w:t}，按出现顺序拼接。 */
    private String textOf(Node paragraph) {
        StringBuilder builder = new StringBuilder();
        NodeList texts = ((org.w3c.dom.Element) paragraph).getElementsByTagNameNS(
                "http://schemas.openxmlformats.org/wordprocessingml/2006/main", "t");
        for (int index = 0; index < texts.getLength(); index++) {
            builder.append(texts.item(index).getTextContent());
        }
        return builder.toString();
    }

    // ── 共用 ────────────────────────────────────────────────────────

    /** 统一换行、去掉零宽字符与全角空格、压缩连续空行。 */
    static String clean(String raw) {
        if (raw == null) {
            return "";
        }
        return raw
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .replace("\u00A0", " ")
                .replace("\u3000", " ")
                .replace("\u200B", "")
                .replaceAll("[ \\t]+\\n", "\n")
                .replaceAll("\n{3,}", "\n\n")
                .trim();
    }

    /** 有意义字符数：只数字母/数字/CJK，空格与标点不算。 */
    static int countMeaningful(String text) {
        int count = 0;
        for (int index = 0; index < text.length(); index++) {
            if (Character.isLetterOrDigit(text.charAt(index))) {
                count++;
            }
        }
        return count;
    }

    private static boolean isPdf(byte[] content, String extension) {
        return startsWith(content, '%', 'P', 'D', 'F') || "pdf".equals(extension);
    }

    private static boolean isDocx(byte[] content, String extension) {
        // docx 就是 zip：魔数 PK\x03\x04。但并非所有 zip 都是 docx，
        // 真正的判据在 readDocumentXml 里（找不到 word/document.xml 就拒）。
        boolean zipped = content.length > 4 && content[0] == 'P' && content[1] == 'K';
        return zipped && ("docx".equals(extension) || extension.isEmpty());
    }

    private static boolean startsWith(byte[] content, int... expected) {
        if (content.length < expected.length) {
            return false;
        }
        for (int index = 0; index < expected.length; index++) {
            if ((content[index] & 0xFF) != (expected[index] & 0xFF)) {
                return false;
            }
        }
        return true;
    }

    private static String extensionOf(String filename) {
        if (filename == null) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static BizException invalid(String message) {
        return new BizException(ErrorCode.INVALID_ARGUMENT, message);
    }
}
