package ai.rever.boss.plugin.dynamic.fluckbrowser.markdown

import ai.rever.boss.plugin.browser.BrowserHandle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

/**
 * Result of Markdown extraction from a browser page or selection.
 */
data class MarkdownExtractionResult(
    val isSelection: Boolean,
    val markdown: String,
    val isTruncated: Boolean,
    val estimatedTokens: Int,
)

@Serializable
private data class ExtractionEnvelope(
    val isSelection: Boolean = false,
    val markdown: String = "",
    val isTruncated: Boolean = false,
    val sourceTitle: String? = null,
    val sourceUrl: String? = null,
)

/**
 * Extracts clean GitHub Flavored Markdown (GFM) from a Fluck Browser tab or active selection.
 *
 * Enforces strict V8 traversal ceilings, filters interactive/hidden noise from tables and code blocks,
 * resolves light-DOM content assigned to open shadow-root slots, and formats prompt-engineered source citations.
 */
object FluckMarkdownExtractor {

    private const val MAX_MARKDOWN_CHARS = 200_000

    private val jsonDecoder = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * JavaScript DOM-to-GFM extraction script injected into the page/frame.
     */
    val EXTRACTOR_SCRIPT: String get() = extractorScript(preferSelection = true)

    internal fun extractorScript(preferSelection: Boolean): String =
        """
        (() => {
          try {
            const MAX_V8_CHARS = 250000;
            const MAX_NODES = 20000;
            const MAX_DEPTH = 100;
            let visitedNodes = 0;
            let resourceLimited = false;
            let selectedRange = null;
            const preferSelection = $preferSelection;
            let document = window.document;
            if (preferSelection) {
              // The public handle executes in the main frame. Follow focused same-origin
              // frames, but never fall back to copying the outer page for a foreign frame.
              for (let depth = 0; depth < MAX_DEPTH; depth++) {
                const active = document.activeElement;
                if (!active || !/^(iframe|frame)$/i.test(active.tagName)) break;
                try {
                  if (!active.contentDocument) return '{"isSelection":false,"markdown":""}';
                  document = active.contentDocument;
                } catch (e) { return '{"isSelection":false,"markdown":""}'; }
              }
            }
            let accumulatedChars = 0;

            const _JSONStringify = JSON.stringify;

            function safeJson(obj) {
              try {
                return _JSONStringify(obj);
              } catch (e) {
                return '{"isSelection":false,"markdown":""}';
              }
            }

            function isNoise(node) {
              if (!node || node.nodeType !== Node.ELEMENT_NODE) return false;
              const tag = (node.tagName || '').toLowerCase();
              if (tag === 'script' || tag === 'style' || tag === 'noscript' || tag === 'svg') return true;

              if (node.hidden || node.getAttribute('aria-hidden') === 'true') return true;
              if (node.style && (node.style.display === 'none' || node.style.visibility === 'hidden')) return true;

              if (node.isConnected && typeof window.getComputedStyle === 'function') {
                try {
                  const style = window.getComputedStyle(node);
                  if (style.display === 'none' || style.visibility === 'hidden') return true;
                } catch (e) {}
              }

              // Interactive buttons, copy buttons, and line numbers
              if (tag === 'button') return true;
              const cls = node.className || '';
              if (typeof cls === 'string') {
                if (cls.includes('copyButton') || cls.includes('copy-button') || cls.includes('line-number')) return true;
              }
              const role = node.getAttribute('role') || '';
              if (role === 'button') return true;

              // Ordinary sidebar clutter
              if (tag === 'nav' || tag === 'footer') return true;
              if (tag === 'aside') {
                const isAdmonition = typeof cls === 'string' && (cls.includes('admonition') || cls.includes('alert') || cls.includes('callout'));
                if (!isAdmonition) return true;
              }

              return false;
            }

            // Detect selection
            let isSelection = false;
            let selectionWasPreformatted = false;
            let detectedLang = '';
            let root = null;

            const selection = preferSelection ? document.defaultView.getSelection() : null;
            if (selection && selection.rangeCount > 0 && !selection.isCollapsed) {
              const text = selection.toString();
              if (text && text.trim().length > 0) {
                isSelection = true;
                const range = selection.getRangeAt(0);

                let commonParent = range.commonAncestorContainer;
                if (commonParent.nodeType === Node.TEXT_NODE) {
                  commonParent = commonParent.parentElement;
                }

                if (commonParent && commonParent.closest) {
                  const pre = commonParent.closest('pre, code, [role="code"], .blob-code');
                  if (pre) {
                    selectionWasPreformatted = true;
                    const code = pre.tagName.toLowerCase() === 'code' ? pre : pre.querySelector('code');
                    if (code && code.className) {
                      const m = code.className.match(/(?:language|lang)-(\w+)/);
                      if (m) detectedLang = m[1];
                    }
                  } else if (typeof window.getComputedStyle === 'function') {
                    try {
                      const cs = window.getComputedStyle(commonParent);
                      if (cs && (cs.whiteSpace === 'pre' || cs.whiteSpace === 'pre-wrap' || cs.whiteSpace === 'pre-line')) {
                        selectionWasPreformatted = true;
                      }
                    } catch (e) {}
                  }
                }

                // Walk the live nodes so stylesheet visibility and relative URLs survive.
                selectedRange = range;
                root = range.commonAncestorContainer;
                for (let ancestor = commonParent; ancestor; ancestor = ancestor.parentElement) {
                  if (isNoise(ancestor)) return safeJson({ isSelection: true, markdown: '' });
                }
              }
            }

            if (!root) {
              isSelection = false;
              root = document.querySelector('article, main, [role="main"]') || document.body;
            }

            function getEffectiveChildren(node) {
              if (!node) return [];
              if ((node.tagName || '').toLowerCase() === 'slot' && node.assignedNodes) {
                const assigned = node.assignedNodes({ flatten: true });
                if (assigned.length) return assigned;
              }
              return (node.shadowRoot || node).childNodes || [];
            }

            function eligible(node, depth) {
              if (!node) return false;
              if (depth > MAX_DEPTH || visitedNodes++ >= MAX_NODES || accumulatedChars >= MAX_V8_CHARS) {
                resourceLimited = true;
                return false;
              }
              if (selectedRange) {
                try { if (!selectedRange.intersectsNode(node)) return false; } catch (e) { return false; }
              }
              return !isNoise(node);
            }

            function textContent(node) {
              let raw = node.textContent || '';
              if (selectedRange) {
                const start = node === selectedRange.startContainer ? selectedRange.startOffset : 0;
                const end = node === selectedRange.endContainer ? selectedRange.endOffset : raw.length;
                raw = raw.substring(start, end);
              }
              let length = Math.min(raw.length, MAX_V8_CHARS - accumulatedChars);
              if (length < raw.length && /[\uD800-\uDBFF]/.test(raw.charAt(length - 1))) length--;
              if (length < raw.length) resourceLimited = true;
              accumulatedChars += length;
              return raw.substring(0, length);
            }

            function escapeText(text) {
              return text.replace(/([\\`*{}\[\]<>#|_])/g, '\\$1');
            }

            function safeUrl(raw, image) {
              try {
                if (!raw || raw.length > 4096) return '';
                const url = new URL(raw, document.baseURI);
                if (!/^https?:$/.test(url.protocol) && (image || url.protocol !== 'mailto:')) return '';
                return url.href.replace(/[<>\\\s()]/g, ch => '%' + ch.charCodeAt(0).toString(16).toUpperCase());
              } catch (e) { return ''; }
            }

            function formatCell(cell, depth = 0) {
              if (!eligible(cell, depth)) return '';
              if (cell.nodeType === Node.TEXT_NODE) return textContent(cell);
              let text = '';
              const children = getEffectiveChildren(cell);
              for (let i = 0; i < children.length; i++) {
                if (accumulatedChars >= MAX_V8_CHARS || visitedNodes >= MAX_NODES) { resourceLimited = true; break; }
                text += formatCell(children[i], depth + 1);
              }
              return text;
            }

            function visibleRow(row, table) {
              for (let parent = row; parent && parent !== table; parent = parent.parentElement) {
                if (isNoise(parent)) return false;
              }
              return true;
            }

            function formatTable(table) {
              // GitHub PR & Issue diff tables
              if (table.classList?.contains('diff-table') || table.querySelector?.('.blob-code')) {
                let diffText = '';
                const diffRows = table.rows || [];
                for (const row of diffRows) {
                  if (accumulatedChars >= MAX_V8_CHARS || visitedNodes >= MAX_NODES) { resourceLimited = true; break; }
                  if (!eligible(row, 0) || !visibleRow(row, table)) continue;
                  const codeCell = row.querySelector('.blob-code');
                  if (codeCell) {
                    diffText += formatCell(codeCell) + '\n';
                  }
                }
                if (diffText.trim()) {
                  const longest = (diffText.match(/`+/g) || []).reduce((n, run) => Math.max(n, run.length), 2);
                  const fence = '`'.repeat(longest + 1);
                  return fence + 'diff\n' + diffText.trimEnd() + '\n' + fence;
                }
              }

              const rows = table.rows || [];
              if (!rows.length) return '';
              let md = '';
              let emittedRows = 0;
              for (const row of rows) {
                if (accumulatedChars >= MAX_V8_CHARS || visitedNodes >= MAX_NODES) { resourceLimited = true; break; }
                if (!eligible(row, 0) || !visibleRow(row, table)) continue;
                const cells = [];
                for (const cell of row.cells || []) {
                  if (accumulatedChars >= MAX_V8_CHARS || visitedNodes >= MAX_NODES) { resourceLimited = true; break; }
                  if (isNoise(cell)) { visitedNodes++; continue; }
                  cells.push(escapeText(formatCell(cell).trim().replace(/\r?\n+/g, ' ')));
                }
                if (cells.length) {
                  md += '| ' + cells.join(' | ') + ' |\n';
                  if (emittedRows++ === 0) {
                    md += '| ' + cells.map(() => '---').join(' | ') + ' |\n';
                  }
                }
              }
              return md.trim();
            }

            function walk(node, depth, inPre) {
              if (!eligible(node, depth)) return '';

              if (node.nodeType === Node.TEXT_NODE) {
                const text = textContent(node);
                if (inPre) return text;
                return escapeText(text.replace(/\s+/g, ' '));
              }

              if (node.nodeType !== Node.ELEMENT_NODE && node.nodeType !== Node.DOCUMENT_FRAGMENT_NODE) {
                return '';
              }

              if (node.nodeType === Node.ELEMENT_NODE && isNoise(node)) {
                return '';
              }

              const tag = (node.tagName || '').toLowerCase();

              // Special container structures
              if (tag === 'pre') {
                let codeEl = node.querySelector('code');
                let lang = '';
                if (codeEl && codeEl.className) {
                  const m = codeEl.className.match(/(?:language|lang)-(\w+)/);
                  if (m) lang = m[1];
                }
                let codeText = '';
                const children = codeEl && isNoise(codeEl) ? [] : getEffectiveChildren(codeEl || node);
                for (let i = 0; i < children.length; i++) {
                  if (accumulatedChars >= MAX_V8_CHARS || visitedNodes >= MAX_NODES) { resourceLimited = true; break; }
                  codeText += walk(children[i], depth + 1, true);
                }
                const matches = codeText.match(/`+/g) || [];
                let maxRun = 2;
                for (let i = 0; i < matches.length; i++) {
                  if (matches[i].length > maxRun) maxRun = matches[i].length;
                }
                const fence = '`'.repeat(maxRun + 1);
                return '\n\n' + fence + lang + '\n' + codeText + '\n' + fence + '\n\n';
              }

              if (tag === 'table') {
                return '\n\n' + formatTable(node) + '\n\n';
              }

              // Alerts / Callouts
              const cls = node.className || '';
              if (typeof cls === 'string') {
                if (cls.includes('markdown-alert-note') || (tag === 'aside' && cls.includes('note'))) {
                  return '\n\n> [!NOTE]\n> ' + walkChildren(node, depth, inPre).trim().replace(/\n/g, '\n> ') + '\n\n';
                }
                if (cls.includes('markdown-alert-tip') || (tag === 'aside' && cls.includes('tip'))) {
                  return '\n\n> [!TIP]\n> ' + walkChildren(node, depth, inPre).trim().replace(/\n/g, '\n> ') + '\n\n';
                }
                if (cls.includes('markdown-alert-important') || (tag === 'aside' && cls.includes('important'))) {
                  return '\n\n> [!IMPORTANT]\n> ' + walkChildren(node, depth, inPre).trim().replace(/\n/g, '\n> ') + '\n\n';
                }
                if (cls.includes('markdown-alert-warning') || (tag === 'aside' && cls.includes('warning'))) {
                  return '\n\n> [!WARNING]\n> ' + walkChildren(node, depth, inPre).trim().replace(/\n/g, '\n> ') + '\n\n';
                }
                if (cls.includes('markdown-alert-caution') || (tag === 'aside' && cls.includes('caution'))) {
                  return '\n\n> [!CAUTION]\n> ' + walkChildren(node, depth, inPre).trim().replace(/\n/g, '\n> ') + '\n\n';
                }
              }

              const children = walkChildren(node, depth, inPre || tag === 'code');

              if (inPre) return tag === 'br' ? '\n' : children;

              switch (tag) {
                case 'h1': return '\n\n# ' + children.trim() + '\n\n';
                case 'h2': return '\n\n## ' + children.trim() + '\n\n';
                case 'h3': return '\n\n### ' + children.trim() + '\n\n';
                case 'h4': return '\n\n#### ' + children.trim() + '\n\n';
                case 'h5': return '\n\n##### ' + children.trim() + '\n\n';
                case 'h6': return '\n\n###### ' + children.trim() + '\n\n';
                case 'p': return '\n\n' + children.trim() + '\n\n';
                case 'br': return '\n';
                case 'hr': return '\n\n---\n\n';
                case 'strong':
                case 'b': return '**' + children.trim() + '**';
                case 'em':
                case 'i': return '*' + children.trim() + '*';
                case 'del':
                case 's':
                case 'strike': return '~~' + children.trim() + '~~';
                case 'code':
                  if (inPre) return children;
                  const runs = children.match(/`+/g) || [];
                  const ticks = '`'.repeat(runs.reduce((longest, run) => Math.max(longest, run.length), 0) + 1);
                  const padding = /^[` ]|[` ]$/.test(children) ? ' ' : '';
                  return ticks + padding + children + padding + ticks;
                case 'blockquote':
                  return '\n\n> ' + children.trim().replace(/\n/g, '\n> ') + '\n\n';
                case 'a': {
                  const href = safeUrl(node.getAttribute('href') || '', false);
                  if (!href || !children.trim()) return children;
                  return '[' + children.trim() + '](<' + href + '>)';
                }
                case 'img': {
                  const src = safeUrl(node.getAttribute('src') || '', true);
                  const alt = escapeText((node.getAttribute('alt') || '').slice(0, 2000));
                  return src ? '\n![' + alt + '](<' + src + '>)\n' : '';
                }
                case 'li': {
                  const isOrdered = node.parentNode && (node.parentNode.tagName || '').toLowerCase() === 'ol';
                  let listDepth = 0;
                  for (let parent = node.parentElement; parent; parent = parent.parentElement) {
                    if (/^(ul|ol)$/i.test(parent.tagName)) listDepth++;
                    if (parent === root) break;
                  }
                  const indentStr = '    '.repeat(Math.max(0, listDepth - 1));
                  let taskPrefix = '';
                  const checkbox = node.querySelector(':scope > input[type="checkbox"], :scope > label > input[type="checkbox"]');
                  if (checkbox) {
                    taskPrefix = checkbox.checked ? '[x] ' : '[ ] ';
                  }
                  const prefix = isOrdered ? '1. ' : '- ';
                  return '\n' + indentStr + prefix + taskPrefix + children.trim();
                }
                case 'ul':
                case 'ol':
                  return '\n' + children + '\n';
                case 'dl': return '\n\n' + children.trim() + '\n\n';
                case 'dt': return '\n**' + children.trim() + '**:\n';
                case 'dd': return '  ' + children.trim() + '\n';
                default:
                  return children;
              }
            }

            function walkChildren(node, depth, inPre) {
              const children = getEffectiveChildren(node);
              let out = '';
              for (let i = 0; i < children.length; i++) {
                if (accumulatedChars >= MAX_V8_CHARS || visitedNodes >= MAX_NODES) { resourceLimited = true; break; }
                const part = walk(children[i], depth + 1, inPre);
                const remaining = MAX_V8_CHARS - out.length;
                out += part.slice(0, remaining);
                if (part.length > remaining || (out.length >= MAX_V8_CHARS && i + 1 < children.length)) {
                  resourceLimited = true;
                  break;
                }
              }
              return out;
            }

            let md = walk(root, 0, selectionWasPreformatted);

            // Fallback to document.body if article/main candidate extracted empty
            if (!isSelection && !md.trim() && root !== document.body && document.body) {
              accumulatedChars = 0;
              visitedNodes = 0;
              md = walk(document.body, 0, false);
            }

            if (isSelection && selectionWasPreformatted && (root.tagName || '').toLowerCase() !== 'pre') {
              const matches = md.match(/`+/g) || [];
              let maxRun = 2;
              for (let i = 0; i < matches.length; i++) {
                if (matches[i].length > maxRun) maxRun = matches[i].length;
              }
              const fence = '`'.repeat(maxRun + 1);
              md = fence + detectedLang + '\n' + md + '\n' + fence;
            }

            // Formatting and attributes also consume space, not only text nodes.
            md = md.trim();
            if (md.length > MAX_V8_CHARS) {
              resourceLimited = true;
              let end = MAX_V8_CHARS;
              if (/[\uD800-\uDBFF]/.test(md.charAt(end - 1))) end--;
              md = md.slice(0, end);
            }
            return safeJson({ isSelection: isSelection, markdown: md, isTruncated: resourceLimited, sourceTitle: document.title, sourceUrl: document.URL });
          } catch (err) {
            return '{"isSelection":false,"markdown":""}';
          }
        })();
        """.trimIndent()

    /**
     * Extracts markdown from [browserHandle], bounding payload size safely without splitting
     * surrogate pairs or leaving unclosed code fences, and formats source citations.
     */
    suspend fun extractMarkdown(
        browserHandle: BrowserHandle,
        focusedFrameScriptRunner: (suspend (String) -> Any?)? = null,
        preferSelection: Boolean = true,
    ): MarkdownExtractionResult = withContext(Dispatchers.IO) {
        val script = extractorScript(preferSelection)
        val rawJsonResult =
            try {
                if (focusedFrameScriptRunner != null) {
                    focusedFrameScriptRunner(script) as? String
                } else {
                    browserHandle.executeJavaScript(script) as? String
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }

        val envelope =
            if (!rawJsonResult.isNullOrBlank()) {
                runCatching {
                    jsonDecoder.decodeFromString<ExtractionEnvelope>(rawJsonResult)
                }.getOrElse {
                    ExtractionEnvelope(isSelection = false, markdown = "")
                }
            } else {
                ExtractionEnvelope(isSelection = false, markdown = "")
            }

        val rawBody = envelope.markdown.trim()
        if (rawBody.isBlank()) {
            return@withContext MarkdownExtractionResult(
                isSelection = envelope.isSelection,
                markdown = "",
                isTruncated = false,
                estimatedTokens = 0,
            )
        }

        val isTruncated = envelope.isTruncated || rawBody.length > MAX_MARKDOWN_CHARS
        val boundedBody =
            if (isTruncated) {
                var cutIndex = minOf(rawBody.length, MAX_MARKDOWN_CHARS)
                // Ensure we do not split a UTF-16 surrogate pair
                if (Character.isHighSurrogate(rawBody[cutIndex - 1])) {
                    cutIndex--
                }
                val sliced = rawBody.substring(0, cutIndex)
                val repaired = repairUnclosedCodeFences(sliced)
                val reason = if (rawBody.length > MAX_MARKDOWN_CHARS) "exceeded 200,000 character limit" else "page extraction limit reached"
                repaired + "\n\n> *[Output truncated: $reason]*"
            } else {
                rawBody
            }

        val pageTitle = (envelope.sourceTitle ?: runCatching { browserHandle.getTitle() }.getOrDefault("")).trim()
        val pageUrl = (envelope.sourceUrl ?: runCatching { browserHandle.getCurrentUrl() }.getOrDefault("")).trim()

        val displayTitle = if (pageTitle.isNotBlank()) pageTitle else pageUrl
        val safeTitle =
            displayTitle.take(200).let { if (it.lastOrNull()?.isHighSurrogate() == true) it.dropLast(1) else it }
                .replace("[\r\n]+".toRegex(), " ")
                .replace("\\", "\\\\")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("[", "\\[")
                .replace("]", "\\]")
                .trim()

        val safeUrl = pageUrl.takeIf { it.length <= 8192 }.orEmpty().lineSequence().joinToString("").trim()
            .takeIf { url ->
                runCatching { java.net.URI(url).scheme?.lowercase() in setOf("http", "https", "file", "about") }
                    .getOrDefault(false)
            }.orEmpty()
        val formattedUrl = "<" + safeUrl
            .replace("<", "%3C").replace(">", "%3E").replace("\\", "%5C") + ">"

        val finalPayload =
            buildString {
                if (safeTitle.isNotBlank() || safeUrl.isNotBlank()) {
                    if (safeUrl.isNotBlank()) {
                        val title = if (safeTitle.isNotBlank()) safeTitle else safeUrl
                        appendLine("> **Source:** [$title]($formattedUrl)")
                    } else {
                        appendLine("> **Source:** $safeTitle")
                    }
                    appendLine("> **Captured from Fluck Browser**")
                    appendLine()
                }
                append(boundedBody)
            }

        val isCodeOrTable = boundedBody.contains("```") || boundedBody.contains("| --- |")
        val estimatedTokens =
            if (isCodeOrTable) {
                ((finalPayload.length * 10) / 32).coerceAtLeast(1)
            } else {
                ((finalPayload.length + 3) / 4).coerceAtLeast(1)
            }

        MarkdownExtractionResult(
            isSelection = envelope.isSelection,
            markdown = finalPayload,
            isTruncated = isTruncated,
            estimatedTokens = estimatedTokens,
        )
    }

    /**
     * Closes any unclosed code fences remaining after payload truncation, matching the
     * exact fence length (e.g. ```, ````, etc.) of the unclosed block.
     */
    internal fun repairUnclosedCodeFences(text: String): String {
        var activeFenceLength = 0
        var activePrefix = ""
        val fenceLine = Regex("""^([ \t]*(?:>[ \t]*)*)(`{3,})(.*)$""")
        for (line in text.lineSequence()) {
            val match = fenceLine.find(line) ?: continue
            val prefix = match.groupValues[1]
            val ticks = match.groupValues[2]
            val suffix = match.groupValues[3]
            if (activeFenceLength == 0) {
                if ('`' !in suffix) {
                    activeFenceLength = ticks.length
                    activePrefix = prefix
                }
            } else if (prefix == activePrefix && ticks.length >= activeFenceLength && suffix.isBlank()) {
                activeFenceLength = 0
            }
        }
        return if (activeFenceLength > 0) {
            text + "\n" + activePrefix + "`".repeat(activeFenceLength)
        } else {
            text
        }
    }

    /**
     * Copies [text] to the system clipboard on [Dispatchers.IO] without re-dispatching
     * back to the UI thread, avoiding EDT freezes during Windows clipboard lock contention.
     */
    suspend fun copyToClipboardSafe(text: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val selection = StringSelection(text)
                val clipboard = Toolkit.getDefaultToolkit().systemClipboard
                clipboard.setContents(selection, selection)
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
        }
}
