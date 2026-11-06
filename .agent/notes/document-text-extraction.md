# 文档文本抽取能力（WORK-076 TASK-001 探针结论）

日期：2026-11-06

## 结论

**不新增依赖、不新写抽取器，直接复用现有 `ResumeTextExtractor`。**

## 依据（源码核对）

- 父 pom 已管理 `pdfbox.version = 3.0.7`；`paideia-ai/pom.xml` 已声明 `org.apache.pdfbox:pdfbox`，注释写明“只读文字层、不引 OCR”（WORK-018）。
- `backend/paideia-ai/src/main/java/io/github/xiaou61/ai/internal/resume/ResumeTextExtractor.java` 已实现：
  - PDF：PDFBox `PDFTextStripper`，`setSortByPosition(true)`（双栏顺序更稳），最多 20 页，超出标记 `truncated`；加密 PDF 明确拒绝。
  - DOCX：JDK zip + XML 解析，关 DTD/外部实体（XXE 防线）、解压总量封顶（zip 炸弹）。
  - 空文字层判定：`countMeaningful < 50` 抛“没有可读的文字（可能是扫描件/图片型 PDF）”，正好覆盖扫描件语义。
- 唯一消费者：`internal/resume/ResumeImportService`（1 处）+ 其测试 `ResumeTextExtractorTest`（夹具用代码现造 PDF/DOCX）。

## 处理方式（对既定设计的修订）

- TASK-003 原计划“新建 `PdfTextExtractor`”改为“**把 `ResumeTextExtractor.extract` 与 `ExtractedText` 提升为 public，并加注释说明它是通用文档文本抽取器**，供 `internal.importing` 复用”。属内部可见性与命名说明的调整，不改变本期范围与验收。
- 不引入新的 PDF/Office 解析库，不改变既有抽取行为；WORK-018 的 `ResumeTextExtractorTest` 保持通过即证明无回归。

## 备注

- 首期只对 PDF 走导入；DOCX 能力已在抽取器里，后续放开格式时无需改抽取层。
- 中文质量：PDFBox 读文字层，中文可读；扫描件（无文字层）按失败处理，不做 OCR（非目标）。
