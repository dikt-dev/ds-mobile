# DS-Mobile 阶段 5 验收记录（文件解析，进行中）

> 日期：2026-10-25  
> 状态：**PDF/TXT 文件选择与本地解析已接入；待用真实文件完成端到端验收**

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

代码：

- `app/src/main/java/com/dsmobile/app/parse/DocumentParser.kt`
- `ChatActivity` 的 `filePicker`

---

## 2. 待验收

- 在 K60 上用真实 PDF/TXT 选择并发送；
- 确认 PDF 文本提取、AI 回复、历史回放；
- 补 DOCX/XLSX/PPTX；
- 补 ZIP/RAR/7z；
- 补扫描 PDF OCR。

---

## 3. 下一步

先完成一次真实 PDF 端到端测试，再扩展 Office/压缩包。
