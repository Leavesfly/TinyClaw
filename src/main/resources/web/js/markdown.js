// TinyClaw Web Console - 本地 Markdown 渲染器（零依赖）
//
// 设计目标：
// 1. 断网可用：随 JAR 本地打包，替代运行时 CDN 依赖（marked），不需要 Node/npm 构建链。
// 2. 安全优先：只输出白名单标签；所有动态文本经 escapeHtml（含引号）转义，
//    链接/图片地址经 isSafeUrl 白名单校验并用 escapeAttr 注入属性，
//    天然免疫 Markdown 注入 XSS，无需额外引入 DOMPurify。
// 3. 行为兼容：暴露 window.marked 兼容对象（setOptions/parse），app.js 现有调用点无需修改；
//    breaks:false 语义与 marked 默认一致（段落内单个换行合并为空格）。
//
// 支持语法：标题、段落、粗斜体、行内代码、围栏代码块（语言 class）、
// 有序/无序列表（一层嵌套）、引用块、GFM 表格、水平线、链接、图片、删除线。
// 不支持的复杂语法按普通文本渲染，不会产生危险输出。

(function () {
    'use strict';

    // ==================== 基础转义 ====================

    /**
     * 文本节点转义：覆盖 & < > " '，含引号以避免被复用到属性上下文时破坏结构。
     */
    function escapeHtml(text) {
        return String(text)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&#39;');
    }

    /**
     * 属性值转义（与 escapeHtml 相同字符集，语义上专用于属性注入点）。
     */
    function escapeAttr(text) {
        return escapeHtml(text);
    }

    /**
     * 链接白名单：仅 http/https、站内绝对路径(/)、锚点(#) 与 data:image。
     * javascript:、vbscript:、file: 及一切协议相对 // 均拒绝，渲染为纯文本。
     */
    function isSafeUrl(url) {
        if (!url) return false;
        const u = String(url).trim().toLowerCase().replace(/[\u0000-\u001f\u007f]/g, '');
        return /^(https?:\/\/|\/|#|data:image\/)/.test(u);
    }

    // ==================== 行内解析 ====================

    /**
     * 行内 Markdown → HTML（输入必须先转义过，或经占位符保护）。
     * 顺序：行内代码 → 图片 → 链接 → 粗斜体 → 粗体 → 斜体 → 删除线。
     */
    function renderInline(text) {
        let out = escapeHtml(text);

        // 行内代码：内容不再解析任何 Markdown，先提取为占位符
        const codeSpans = [];
        out = out.replace(/`([^`]+)`/g, (m, code) => {
            codeSpans.push('<code>' + code + '</code>');
            return '\u0000CODE' + (codeSpans.length - 1) + '\u0000';
        });

        // 图片 ![alt](url)
        out = out.replace(/!\[([^\]]*)\]\(([^)\s]+)(?:\s+&quot;([^&]*)&quot;)?\)/g, (m, alt, url, title) => {
            if (!isSafeUrl(url)) return m;
            const titleAttr = title ? ' title="' + escapeAttr(title) + '"' : '';
            return '<img src="' + escapeAttr(url) + '" alt="' + escapeAttr(alt) + '"' + titleAttr + '>';
        });

        // 链接 [text](url)：文本部分保留已解析的行内格式（粗体等）
        out = out.replace(/\[([^\]]+)\]\(([^)\s]+)(?:\s+&quot;([^&]*)&quot;)?\)/g, (m, label, url, title) => {
            if (!isSafeUrl(url)) return m;
            const titleAttr = title ? ' title="' + escapeAttr(title) + '"' : '';
            return '<a href="' + escapeAttr(url) + '" target="_blank" rel="noopener noreferrer"' + titleAttr + '>'
                + label + '</a>';
        });

        // 粗斜体 ***x*** / ___x___
        out = out.replace(/(\*\*\*|___)(?=\S)(.+?)(?<=\S)\1/g, '<strong><em>$2</em></strong>');
        // 粗体 **x** / __x__
        out = out.replace(/(\*\*|__)(?=\S)(.+?)(?<=\S)\1/g, '<strong>$2</strong>');
        // 斜体 *x* / _x_（避免命中词中下划线：要求两侧非空白且前字符非字母数字）
        out = out.replace(/(^|[^\w\\])\*(?=\S)([^*]+?)(?<=\S)\*(?![\w])/g, '$1<em>$2</em>');
        out = out.replace(/(^|[^\w\\])_(?=\S)([^_]+?)(?<=\S)_(?![\w])/g, '$1<em>$2</em>');
        // 删除线 ~~x~~
        out = out.replace(/~~(?=\S)(.+?)(?<=\S)~~/g, '<del>$1</del>');

        // 还原行内代码占位符
        out = out.replace(/\u0000CODE(\d+)\u0000/g, (m, idx) => codeSpans[parseInt(idx, 10)]);

        return out;
    }

    // ==================== 块级解析 ====================

    const LIST_ORDERED = 'ol';
    const LIST_UNORDERED = 'ul';

    /**
     * 判断一行是否为列表项，返回 {marker, ordered, indent, text} 或 null。
     * 支持 - / * / + 与 1. 形式；缩进 >= 2 空格视为嵌套层级。
     */
    function parseListItem(line) {
        const m = line.match(/^(\s*)([-*+]|(\d+)[.)])\s+(.*)$/);
        if (!m) return null;
        const indent = Math.floor(m[1].length / 2);
        return {
            indent: Math.min(indent, 1), // 最多支持一层嵌套
            ordered: !!m[3],
            text: m[4]
        };
    }

    /**
     * 判断表格分隔行：| --- | :---: | 形式。
     */
    function isTableDivider(line) {
        const trimmed = line.trim();
        if (!trimmed.startsWith('|')) return false;
        const cells = trimmed.slice(1, trimmed.endsWith('|') ? -1 : undefined).split('|');
        if (cells.length === 0) return false;
        return cells.every(c => /^:?-{2,}:?$/.test(c.trim()));
    }

    /**
     * 解析表格行 → 单元格数组。
     */
    function splitTableRow(line) {
        const trimmed = line.trim();
        const body = trimmed.startsWith('|') ? trimmed.slice(1) : trimmed;
        const content = body.endsWith('|') ? body.slice(0, -1) : body;
        return content.split('|').map(c => c.trim());
    }

    /**
     * Markdown 全文 → HTML。逐行状态机，未识别语法按段落文本处理。
     */
    function renderBlocks(src) {
        const lines = String(src).replace(/\r\n?/g, '\n').split('\n');
        let html = [];
        let paragraph = [];      // 段落累积行
        let i = 0;

        /** 段落收尾：行间以空格连接（breaks:false 语义），整体做行内解析 */
        const flushParagraph = () => {
            if (paragraph.length === 0) return;
            html.push('<p>' + renderInline(paragraph.join(' ').trim()) + '</p>');
            paragraph = [];
        };

        while (i < lines.length) {
            const line = lines[i];
            const trimmed = line.trim();

            // 空行：段落边界
            if (trimmed === '') {
                flushParagraph();
                i++;
                continue;
            }

            // 围栏代码块 ```lang ... ```
            const fence = line.match(/^```(\S*)\s*$/);
            if (fence) {
                flushParagraph();
                const lang = fence[1];
                const codeLines = [];
                i++;
                while (i < lines.length && !/^```\s*$/.test(lines[i])) {
                    codeLines.push(lines[i]);
                    i++;
                }
                i++; // 跳过结束围栏（文件末尾未闭合时兼容收尾）
                const langClass = lang ? ' class="language-' + escapeAttr(lang) + '"' : '';
                html.push('<pre><code' + langClass + '>' + escapeHtml(codeLines.join('\n')) + '</code></pre>');
                continue;
            }

            // ATX 标题 # ~ ######
            const heading = line.match(/^(#{1,6})\s+(.*)$/);
            if (heading) {
                flushParagraph();
                const level = heading[1].length;
                html.push('<h' + level + '>' + renderInline(heading[2].replace(/\s+#+\s*$/, '')) + '</h' + level + '>');
                i++;
                continue;
            }

            // 水平线 --- / *** / ___（避免与列表标记冲突：要求行内仅有该符号）
            if (/^ {0,3}((-\s*){3,}|(\*\s*){3,}|(_\s*){3,})$/.test(line)) {
                flushParagraph();
                html.push('<hr>');
                i++;
                continue;
            }

            // 引用块 >：连续行合并后递归渲染（去掉一层引用标记）
            if (/^ {0,3}>/.test(line)) {
                flushParagraph();
                const quoteLines = [];
                while (i < lines.length && /^ {0,3}>/.test(lines[i])) {
                    quoteLines.push(lines[i].replace(/^ {0,3}>\s?/, ''));
                    i++;
                }
                html.push('<blockquote>' + renderBlocks(quoteLines.join('\n')) + '</blockquote>');
                continue;
            }

            // GFM 表格：当前行为表头且下一行是分隔行
            if (i + 1 < lines.length && trimmed.includes('|') && isTableDivider(lines[i + 1])) {
                flushParagraph();
                const headerCells = splitTableRow(line);
                const aligns = splitTableRow(lines[i + 1]).map(c => {
                    const t = c.trim();
                    if (t.startsWith(':') && t.endsWith(':')) return ' style="text-align:center"';
                    if (t.endsWith(':')) return ' style="text-align:right"';
                    return '';
                });
                i += 2;
                const bodyRows = [];
                while (i < lines.length && lines[i].trim() !== '' && lines[i].includes('|')) {
                    bodyRows.push(splitTableRow(lines[i]));
                    i++;
                }
                let table = '<table><thead><tr>'
                    + headerCells.map((c, ci) => '<th' + (aligns[ci] || '') + '>' + renderInline(c) + '</th>').join('')
                    + '</tr></thead><tbody>';
                for (const row of bodyRows) {
                    table += '<tr>' + headerCells.map((c, ci) =>
                        '<td' + (aligns[ci] || '') + '>' + renderInline(row[ci] || '') + '</td>').join('') + '</tr>';
                }
                table += '</tbody></table>';
                html.push(table);
                continue;
            }

            // 列表：连续列表行合并渲染（支持一层嵌套）
            const firstItem = parseListItem(line);
            if (firstItem) {
                flushParagraph();
                let items = [];
                while (i < lines.length) {
                    const item = parseListItem(lines[i]);
                    if (!item) {
                        // 列表项的续行（缩进文本）并入前一项
                        if (items.length > 0 && /^\s+\S/.test(lines[i]) && lines[i].trim() !== '') {
                            items[items.length - 1].text += ' ' + lines[i].trim();
                            i++;
                            continue;
                        }
                        break;
                    }
                    items.push(item);
                    i++;
                }
                html.push(renderList(items));
                continue;
            }

            // 普通文本行：累积进段落
            paragraph.push(trimmed);
            i++;
        }
        flushParagraph();
        return html.join('\n');
    }

    /**
     * 列表条目数组 → <ul>/<ol> HTML。indent>0 视为上一层最后一项的嵌套子列表。
     */
    function renderList(items) {
        let html = '';
        let root = null;          // 根列表类型（首个条目决定）
        let current = null;
        let index = 0;
        for (const item of items) {
            if (root === null) {
                root = item.ordered ? LIST_ORDERED : LIST_UNORDERED;
                current = { ordered: item.ordered, items: [] };
            }
            if (item.indent === 0) {
                if (current.ordered !== item.ordered) {
                    // 同级但标记类型切换：先闭合再重开（简化处理，marked 亦如此分段）
                    html += renderFlatList(current);
                    current = { ordered: item.ordered, items: [] };
                }
                current.items.push({ text: item.text, nested: null });
                index = current.items.length - 1;
            } else {
                const parent = current.items[index];
                if (parent) {
                    parent.nested = parent.nested || [];
                    parent.nested.push({ text: item.text });
                }
            }
        }
        if (current) html += renderFlatList(current);
        return html;
    }

    /**
     * 单层列表渲染；嵌套项作为 <li> 内的子 <ul>。
     */
    function renderFlatList(list) {
        if (!list || list.items.length === 0) return '';
        const tag = list.ordered ? LIST_ORDERED : LIST_UNORDERED;
        let html = '<' + tag + '>';
        let n = 1;
        for (const item of list.items) {
            const value = list.ordered ? ' value="' + (n++) + '"' : '';
            html += '<li' + value + '>' + renderInline(item.text);
            if (item.nested && item.nested.length > 0) {
                html += '<ul>' + item.nested.map(sub =>
                    '<li>' + renderInline(sub.text) + '</li>').join('') + '</ul>';
            }
            html += '</li>';
        }
        return html + '</' + tag + '>';
    }

    // ==================== 对外接口 ====================

    const options = { breaks: false };

    /**
     * 渲染入口。空输入返回空字符串。
     */
    function parse(src) {
        if (src == null) return '';
        const text = String(src);
        if (text.trim() === '') return '';
        return renderBlocks(text);
    }

    // window.marked 兼容对象：app.js 现有 marked.setOptions / marked.parse 调用点零改动
    window.marked = {
        setOptions(next) {
            if (next && typeof next.breaks === 'boolean') options.breaks = next.breaks;
        },
        parse(src) {
            // breaks:true 时段落内单换行转 <br>；当前 app.js 固定 breaks:false
            if (options.breaks) {
                return renderBlocks(String(src == null ? '' : src).replace(/\n/g, '  \n'));
            }
            return parse(src);
        }
    };

    // 独立命名入口，供后续新模块直接使用（避免依赖 marked 兼容层）
    window.tinyMarkdown = {
        parse: parse,
        escapeHtml: escapeHtml,
        isSafeUrl: isSafeUrl
    };
})();
