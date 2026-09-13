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
    val EXTRACTOR_SCRIPT: String =
        """
        (() => {
          try {
            const MAX_V8_CHARS = 250000;
            let accumulatedChars = 0;

            const _ArrayFrom = Array.from;
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

            const selection = window.getSelection();
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

                root = range.cloneContents();
              }
            }

            if (!root) {
              isSelection = false;
              root = document.querySelector('article, main, [role="main"]') || document.body;
            }

            function getEffectiveChildren(node) {
              if (!node) return [];
              const result = [];
              if (node.shadowRoot) {
                // Resolve shadow children and slot light-DOM nodes
                const shadowChildren = _ArrayFrom(node.shadowRoot.childNodes);
                for (let i = 0; i < shadowChildren.length; i++) {
                  const child = shadowChildren[i];
                  if (child.tagName && child.tagName.toLowerCase() === 'slot') {
                    if (typeof child.assignedNodes === 'function') {
                      const assigned = child.assignedNodes({ flatten: true });
                      for (let j = 0; j < assigned.length; j++) result.push(assigned[j]);
                    }
                  } else {
                    result.push(child);
                  }
                }
              }
              if (result.length > 0) return result;
              return _ArrayFrom(node.childNodes || []);
            }

            function formatCell(cell) {
              let text = '';
              const children = getEffectiveChildren(cell);
              for (let i = 0; i < children.length; i++) {
                const child = children[i];
                if (isNoise(child)) continue;
                if (child.nodeType === Node.TEXT_NODE) {
                  text += child.textContent || '';
                } else if (child.nodeType === Node.ELEMENT_NODE) {
                  text += formatCell(child);
                }
              }
              return text;
            }

            function formatTable(table) {
              // GitHub PR & Issue diff tables
              if (table.classList?.contains('diff-table') || table.querySelector?.('.blob-code')) {
                let diffText = '';
                let diffRows = [];
                if (table.querySelectorAll) {
                  diffRows = _ArrayFrom(table.querySelectorAll(':scope > tr, :scope > thead > tr, :scope > tbody > tr, :scope > tfoot > tr'));
                }
                if (!diffRows.length && table.rows) diffRows = _ArrayFrom(table.rows);
                diffRows.forEach(row => {
                  const codeCell = row.querySelector('.blob-code');
                  if (codeCell) {
                    diffText += formatCell(codeCell) + '\n';
                  }
                });
                if (diffText.trim()) {
                  return '```diff\n' + diffText.trimEnd() + '\n```';
                }
              }

              let rows = [];
              if (table.querySelectorAll) {
                rows = _ArrayFrom(table.querySelectorAll(':scope > tr, :scope > thead > tr, :scope > tbody > tr, :scope > tfoot > tr'));
              }
              if (!rows.length && table.rows) {
                rows = _ArrayFrom(table.rows);
              }
              if (!rows.length) return '';
              let md = '';
              rows.forEach((row, i) => {
                const cells = _ArrayFrom(row.querySelectorAll(':scope > th, :scope > td')).map(c => 
                  formatCell(c).trim().replace(/\r?\n+/g, ' ').replace(/\|/g, '\\|')
                );
                if (cells.length) {
                  md += '| ' + cells.join(' | ') + ' |\n';
                  if (i === 0) {
                    md += '| ' + cells.map(() => '---').join(' | ') + ' |\n';
                  }
                }
              });
              return md.trim();
            }

            function walk(node, depth, inPre) {
              if (!node || accumulatedChars >= MAX_V8_CHARS) return '';

              if (node.nodeType === Node.TEXT_NODE) {
                const raw = node.textContent || '';
                if (!raw) return '';
                const remaining = MAX_V8_CHARS - accumulatedChars;
                const text = raw.length > remaining ? raw.substring(0, remaining) : raw;
                accumulatedChars += text.length;
                if (inPre) return text;
                return text.replace(/\s+/g, ' ');
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
                const children = getEffectiveChildren(codeEl || node);
                for (let i = 0; i < children.length; i++) {
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

              const children = walkChildren(node, depth, inPre);

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
                  return '`' + children.replace(/`/g, '\\`') + '`';
                case 'blockquote':
                  return '\n\n> ' + children.trim().replace(/\n/g, '\n> ') + '\n\n';
                case 'a': {
                  const href = node.getAttribute('href');
                  if (!href || !children.trim()) return children;
                  return '[' + children.trim() + '](' + href + ')';
                }
                case 'img': {
                  const src = node.getAttribute('src');
                  const alt = node.getAttribute('alt') || '';
                  return src ? '\n![' + alt + '](' + src + ')\n' : '';
                }
                case 'li': {
                  const isOrdered = node.parentNode && (node.parentNode.tagName || '').toLowerCase() === 'ol';
                  const indentStr = '  '.repeat(Math.max(0, depth - 1));
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
                if (accumulatedChars >= MAX_V8_CHARS) break;
                out += walk(children[i], depth + 1, inPre);
              }
              return out;
            }

            let md = walk(root, 0, selectionWasPreformatted);

            // Fallback to document.body if article/main candidate extracted empty
            if (!isSelection && !md.trim() && root !== document.body && document.body) {
              accumulatedChars = 0;
              md = walk(document.body, 0, false);
            }

            if (isSelection && selectionWasPreformatted && !/^`{3,}/.test(md.trimStart())) {
              const matches = md.match(/`+/g) || [];
              let maxRun = 2;
              for (let i = 0; i < matches.length; i++) {
                if (matches[i].length > maxRun) maxRun = matches[i].length;
              }
              const fence = '`'.repeat(maxRun + 1);
              md = fence + detectedLang + '\n' + md + '\n' + fence;
            }

            return safeJson({ isSelection: isSelection, markdown: md.trim() });
          } catch (err) {
            return safeJson({ isSelection: false, markdown: '' });
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
    ): MarkdownExtractionResult {
        val rawJsonResult =
            try {
                if (focusedFrameScriptRunner != null) {
                    focusedFrameScriptRunner(EXTRACTOR_SCRIPT) as? String
                } else {
                    browserHandle.executeJavaScript(EXTRACTOR_SCRIPT) as? String
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
            return MarkdownExtractionResult(
                isSelection = envelope.isSelection,
                markdown = "",
                isTruncated = false,
                estimatedTokens = 0,
            )
        }

        val isTruncated = rawBody.length > MAX_MARKDOWN_CHARS
        val boundedBody =
            if (isTruncated) {
                var cutIndex = MAX_MARKDOWN_CHARS
                // Ensure we do not split a UTF-16 surrogate pair
                if (Character.isHighSurrogate(rawBody[cutIndex - 1])) {
                    cutIndex--
                }
                val sliced = rawBody.substring(0, cutIndex)
                val repaired = repairUnclosedCodeFences(sliced)
                repaired + "\n\n> *[Output truncated: exceeded 200,000 character limit]*"
            } else {
                rawBody
            }

        val pageTitle = runCatching { browserHandle.getTitle() }.getOrDefault("").trim()
        val pageUrl = runCatching { browserHandle.getCurrentUrl() }.getOrDefault("").trim()

        val displayTitle = if (pageTitle.isNotBlank()) pageTitle else pageUrl
        val safeTitle =
            displayTitle
                .replace("[\r\n]+".toRegex(), " ")
                .replace("[", "\\[")
                .replace("]", "\\]")
                .trim()
                .take(200)

        val safeUrl = pageUrl.replace("[\r\n]+".toRegex(), "").trim()
        val formattedUrl =
            if (safeUrl.contains("(") || safeUrl.contains(")")) {
                "<$safeUrl>"
            } else {
                safeUrl
            }

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

        return MarkdownExtractionResult(
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
        val lines = text.lines()
        for (line in lines) {
            val trimmedLeading = line.trimStart()
            if (activeFenceLength == 0) {
                val match = Regex("""^(`{3,})""").find(trimmedLeading)
                if (match != null) {
                    activeFenceLength = match.groupValues[1].length
                }
            } else {
                val closeMatch = Regex("""^(`{$activeFenceLength,})\s*$""").find(trimmedLeading)
                if (closeMatch != null) {
                    activeFenceLength = 0
                }
            }
        }
        return if (activeFenceLength > 0) {
            text + "\n" + "`".repeat(activeFenceLength)
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
