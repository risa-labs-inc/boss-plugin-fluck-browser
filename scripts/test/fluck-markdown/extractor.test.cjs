const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { JSDOM } = require('jsdom');

// Execute the shipped script against a real DOM, rather than checking for source substrings.
const source = fs.readFileSync(path.resolve(__dirname,
  '../../../src/main/kotlin/ai/rever/boss/plugin/dynamic/fluckbrowser/markdown/FluckMarkdownExtractor.kt'), 'utf8');
const script = source.split('"""')[1].replace('$preferSelection', 'true');

function extract(html, setup, preferSelection = true) {
  const dom = new JSDOM(html, { url: 'https://example.com/docs', runScripts: 'outside-only' });
  try {
    setup?.(dom.window);
    return JSON.parse(dom.window.eval(script.replace('const preferSelection = true;', `const preferSelection = ${preferSelection};`)));
  } finally {
    dom.window.close();
  }
}

test('page code preserves leading indentation, trailing spaces and blank lines', () => {
  const code = '    first()  \n\n\n    second()';
  const result = extract(`<pre><code class="language-python">${code}</code></pre>`);
  assert.equal(result.markdown, '```python\n' + code + '\n```');
});

test('code traversal does not restore hidden text, line numbers or copy buttons', () => {
  const result = extract('<pre><code><span class="line-number">1</span>let x = 1;<span hidden>hidden</span><button>Copy</button></code></pre>');
  assert.equal(result.markdown, '```\nlet x = 1;\n```');
});

test('a selected code fragment retains its leading indentation', () => {
  const code = '    first()\n    second()';
  const result = extract(`<pre><code>${code}</code></pre>`, window => {
    const range = window.document.createRange();
    range.selectNodeContents(window.document.querySelector('code'));
    window.getSelection().addRange(range);
  });
  assert.equal(result.isSelection, true);
  assert.equal(result.markdown, '```\n' + code + '\n```');
});

for (const [kind, expected] of [['note', 'NOTE'], ['tip', 'TIP'], ['important', 'IMPORTANT'], ['warning', 'WARNING'], ['caution', 'CAUTION']]) {
  test(`GitHub ${kind} alert is classified by its type`, () => {
    const result = extract(`<div class="markdown-alert markdown-alert-${kind}"><p>Keep this</p></div>`);
    assert.ok(result.markdown.startsWith(`> [!${expected}]\n`), result.markdown);
    assert.ok(result.markdown.includes('Keep this'));
  });
}

test('an aside callout survives while ordinary sidebar clutter is omitted', () => {
  const result = extract('<main><aside>Sidebar</aside><aside class="admonition note"><p>Keep this</p></aside></main>');
  assert.ok(result.markdown.startsWith('> [!NOTE]'));
  assert.ok(result.markdown.includes('Keep this'));
  assert.ok(!result.markdown.includes('Sidebar'));
});

test('a selected aside callout is not removed by selection cleanup', () => {
  const result = extract('<main><aside class="admonition note"><p>Keep this</p></aside></main>', window => {
    const range = window.document.createRange();
    range.selectNodeContents(window.document.querySelector('main'));
    window.getSelection().addRange(range);
  });
  assert.equal(result.isSelection, true);
  assert.ok(result.markdown.startsWith('> [!NOTE]'));
  assert.ok(result.markdown.includes('Keep this'));
});

test('table formatting filters out hidden spans and buttons in cells', () => {
  const result = extract('<table><tr><td>Visible<span hidden>HIDDEN</span><button>Copy</button></td></tr></table>');
  assert.ok(result.markdown.includes('| Visible |'), result.markdown);
  assert.ok(!result.markdown.includes('HIDDEN'), result.markdown);
  assert.ok(!result.markdown.includes('Copy'), result.markdown);
});

test('resource ceiling strictly bounds massive text node allocation', () => {
  const hugeText = 'x'.repeat(1_000_000);
  const result = extract(`<pre><code>${hugeText}</code></pre>`);
  assert.ok(result.markdown.length <= 250_100, `Result length ${result.markdown.length} exceeded 250,100`);
});

test('shadow root slots resolve light DOM assigned text', () => {
  const html = '<div id="host"><span>Light text</span></div>';
  const result = extract(html, window => {
    const host = window.document.getElementById('host');
    if (host.attachShadow) {
      const shadow = host.attachShadow({ mode: 'open' });
      shadow.innerHTML = '<slot></slot>';
    }
  });
  assert.ok(result.markdown.includes('Light text'), result.markdown);
});


test('tables and diff tables share the text budget', () => {
  for (const attrs of ['', 'class="diff-table"']) {
    const result = extract(`<table ${attrs}><tr><td class="blob-code">${'x'.repeat(1_000_000)}</td></tr></table><p>AFTER</p>`);
    assert.ok(result.markdown.length <= 250_100);
    assert.ok(!result.markdown.includes('AFTER'));
  }
  const result = extract(`<table><tr><td>${'x'.repeat(1_000_000)}</td></tr></table>`);
  assert.ok(result.markdown.length <= 250_100);
});

test('hidden rows, sections, cells and code elements stay hidden', () => {
  const result = extract('<table><tbody hidden><tr><td>HIDDEN SECTION</td></tr></tbody><tbody><tr hidden><td>HIDDEN ROW</td></tr><tr><td hidden>HIDDEN CELL</td><td>Visible</td></tr></tbody></table><pre><code hidden>HIDDEN CODE</code></pre>');
  assert.ok(!result.markdown.includes('HIDDEN'), result.markdown);
  assert.ok(result.markdown.includes('Visible'));
});

test('selection retains stylesheet visibility and exact text boundaries', () => {
  const html = '<style>.secret { display:none }</style><p>Before <span class="secret">HIDDEN</span><b>selected</b> after</p>';
  const result = extract(html, window => {
    const p = window.document.querySelector('p');
    const range = window.document.createRange();
    range.setStart(p.firstChild, 3);
    range.setEnd(p.lastChild, 3);
    window.getSelection().addRange(range);
  });
  assert.equal(result.isSelection, true);
  assert.ok(!result.markdown.includes('HIDDEN'));
  assert.equal(result.markdown, 'ore **selected** af');
});

test('page mode ignores a live selection', () => {
  const result = extract('<p>First</p><p>Second</p>', window => {
    const range = window.document.createRange();
    range.selectNodeContents(window.document.querySelector('p'));
    window.getSelection().addRange(range);
  }, false);
  assert.equal(result.isSelection, false);
  assert.ok(result.markdown.includes('First'));
  assert.ok(result.markdown.includes('Second'));
});

test('slots nested inside shadow markup resolve assigned nodes once', () => {
  const result = extract('<div id="host"><span>Light text</span></div>', window => {
    window.document.querySelector('#host').attachShadow({ mode:'open' }).innerHTML = '<section><slot></slot></section>';
  });
  assert.equal(result.markdown, 'Light text');
});

test('empty shadow roots do not expose unrendered light DOM', () => {
  const result = extract('<div id="host">UNRENDERED</div><p>Visible</p>', window => {
    window.document.querySelector('#host').attachShadow({ mode:'open' });
  });
  assert.equal(result.markdown, 'Visible');
});

test('links resolve relative destinations and omit executable schemes', () => {
  const result = extract('<a href="../path?q=(x)">Docs</a><a href="javascript:alert(1)">Unsafe</a><img src="/picture.png" alt="[pic]"><a>Plain</a>');
  assert.ok(result.markdown.includes('[Docs](<https://example.com/path?q=%28x%29>)'), result.markdown);
  assert.ok(!result.markdown.includes('javascript:'));
  assert.ok(result.markdown.includes('Unsafe'));
  assert.ok(result.markdown.includes('![\\[pic\\]](<https://example.com/picture.png>)'));
  assert.ok(!result.markdown.includes('[Plain]'));
});

test('literal markup in text stays literal', () => {
  const result = extract('<p>[link](javascript:alert(1)) *literal* &lt;script&gt;</p>');
  assert.equal(result.markdown, '\\[link\\](javascript:alert(1)) \\*literal\\* \\<script\\>');
});

test('inline code uses a delimiter longer than embedded backticks', () => {
  const result = extract('<p><code>a`b</code> <code>`x`</code></p>');
  assert.equal(result.markdown, '``a`b`` `` `x` ``');
});

test('list indentation depends on list nesting rather than layout divs', () => {
  const result = extract('<main><div><section><ul><li>One<ul><li>Two</li></ul></li><li>Three</li></ul></section></div></main>');
  assert.deepEqual(result.markdown.split('\n').filter(line => line.trim()), ['- One', '    - Two', '- Three']);
});

test('script exceptions return a valid empty envelope', () => {
  const result = extract('<p>Content</p>', window => {
    window.getSelection = () => { throw new Error('unavailable'); };
  });
  assert.deepEqual(result, { isSelection: false, markdown: '' });
});

test('formatting-only and deeply nested pages remain bounded', () => {
  const result = extract('<p>' + '<br>'.repeat(30000) + '</p><p>AFTER</p>');
  assert.ok(!result.markdown.includes('AFTER'));
  const deep = extract('<div>'.repeat(150) + 'TOO DEEP' + '</div>'.repeat(150) + '<p>Visible</p>');
  assert.ok(!deep.markdown.includes('TOO DEEP'));
  assert.ok(deep.markdown.includes('Visible'));
});


test('focused same-origin frames supply selection and its source', () => {
  const result = extract('<p>OUTER PAGE</p><iframe></iframe>', window => {
    const frame = window.document.querySelector('iframe');
    frame.contentDocument.body.innerHTML = '<p>Frame text</p>';
    frame.contentDocument.title = 'Frame title';
    frame.focus();
    const range = frame.contentDocument.createRange();
    range.selectNodeContents(frame.contentDocument.querySelector('p'));
    frame.contentWindow.getSelection().addRange(range);
  });
  assert.equal(result.isSelection, true);
  assert.equal(result.markdown, 'Frame text');
  assert.equal(result.sourceTitle, 'Frame title');
});

test('inaccessible focused frame never falls back to copying the outer page', () => {
  const result = extract('<p>OUTER PAGE</p><iframe></iframe>', window => {
    const frame = window.document.querySelector('iframe');
    frame.focus();
    Object.defineProperty(frame, 'contentDocument', { get() { return null; } });
  });
  assert.equal(result.markdown, '');
});

test('traversal ceilings are reported even when output is below the character limit', () => {
  const result = extract('<p>Visible</p>' + '<div>'.repeat(150) + 'DEEP' + '</div>'.repeat(150));
  assert.equal(result.isTruncated, true);
  assert.ok(result.markdown.includes('Visible'));
});


test('diff fences and table text cannot create unintended markdown', () => {
  const diff = extract('<table class="diff-table"><tr><td class="blob-code">```</td></tr></table>');
  assert.equal(diff.markdown, '````diff\n```\n````');
  const table = extract('<table><tr><td>[link](javascript:alert(1))</td></tr></table>');
  assert.ok(table.markdown.includes('\\[link\\]'));
});


test('preformatted text preserves highlighted content without injecting formatting', () => {
  const result = extract('<pre><code><b>let</b> x = 1;<br><i>next()</i></code></pre>');
  assert.equal(result.markdown, '```\nlet x = 1;\nnext()\n```');
});

test('selected code beginning with backticks still receives an outer fence', () => {
  const result = extract('<pre><code>```sample</code></pre>', window => {
    const range = window.document.createRange();
    range.selectNodeContents(window.document.querySelector('code'));
    window.getSelection().addRange(range);
  });
  assert.equal(result.markdown, '````\n```sample\n````');
});
