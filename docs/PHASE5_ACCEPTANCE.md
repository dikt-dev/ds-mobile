# DS-Mobile 阶段 5 验收记录（文件解析，进行中）

> 日期：2026-10-25  
> 状态：**PDF/TXT 选择、解析、发送已在 K60 上通过；Office/压缩包待做**

---

## 1. 已实现

| 项 | 说明 |
|---|---|
| 文件入口 | 聊天输入栏「文件」按钮，`OpenDocument` |
| 支持类型 | PDF、text/* |
| PDF 解析 | PDFBox-Android `PDFTextStripper` |
| 文本解码 | UTF-8，异常时回退 GBK |
| 限制 | 单文件 ≤ 20MB，输出 ≤ 30,000 字符 |
| 发送 | 解析文本以 `【文件：name】` 前缀发给 Worker |
| 失败提示 | 扫描 PDF / 空文本会明确提示 |
| K60 实测 | ✅ 选择 50 页 spike.pdf → 本地解析 → AI 回复 |

截图：

- `screenshot-p5-pdf.png`：PDF 文本 + AI 回复

---

## 2. 实测链路

```text
文件按钮 → DocumentsUI Downloads → 选择 spike.pdf
→ DocumentParser（PDFBox 提取 50 页文本）
→ POST /v1/chat/completions（【文件：spike.pdf】+ 文本）
→ AI 回复
```

AI 回复示例：

```text
（把那份 PDF 翻来覆去看了三遍...）五十页，页页都写“page X / 50”？
...能读到第 50 页倒说明你的分页没崩。
```

---

## 3. 待验收/下一步

- DOCX/XLSX/PPTX；
- ZIP/RAR/7z；
- 扫描 PDF OCR；
- 超长文本分段/摘要；
- 文件发送 outbox。
