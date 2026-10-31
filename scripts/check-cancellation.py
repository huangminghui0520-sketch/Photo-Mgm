#!/usr/bin/env python3
"""CI 拦截：协程取消信号被吞（CancellationException swallowed）。

背景
----
JVM 上 kotlinx.coroutines.CancellationException 是 java.util.concurrent.CancellationException
的 typealias，**继承 IllegalStateException**。因此下面两种写法都会把取消信号吞掉：

    runCatching { ... }                 // 捕获 Throwable
    try { ... } catch (e: Exception) {} // 捕获 Exception（含取消）

后果：协程已被取消，代码却继续跑完并把状态写成"失败"，外层感知不到取消。
本轮（2026-10-31）exportZip / prepareLedger 就是这样漏掉的（S1/S2）。逐处靠人肉 review
不可靠，故固化为两条可机器判定的规则。

规则
----
R1  catch(Exception/Throwable/…) 必须配取消分支
    在"存在协程语义"的文件里（含 suspend / launch / async / withContext / coroutineScope /
    runBlocking），同一个 try 若有 catch 宽泛异常，则该 try 必须另有
    `catch (e: CancellationException)`，或 catch 体内调用 ensureActive()。

R2  协程上下文内禁止裸 runCatching
    runCatching 的最近外层作用域若是 suspend fun / launch / async / runBlocking / withContext /
    coroutineScope，须改用 app 模块的 runCatchingCancellable（取消信号重抛）。
    非 suspend 的纯函数（XlsxWriter / SettingsStore / FileHasher 等）内部没有挂起点，
    取消不可能传入，用普通 runCatching 是**允许**的，本规则不会误报。

用法
----
    python3 scripts/check-cancellation.py [路径 ...]     # 默认扫 app algorithm data
    退出码 0=通过  1=发现违规

豁免
----
    在违规行末尾加注释 `// ci-ok: 原因`，可单独豁免该处（需写明理由）。
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

# ---------- 词法：去掉注释与字符串，避免把注释里的代码当成真代码 ----------

def strip_noise(src: str) -> str:
    """把注释与字符串内容替换为等长空白，保留行号与列位，便于按行匹配。

    ★ 必须是**带状态**的扫描：早期版本先判 `//` 再判字符串，结果把
      `/** …（用户可见位置：content:// 或本地路径）*/` 里的 `content://`
      当成行注释，整行被抹掉后 `*/` 丢失，后续所有行的括号深度全部错位。
    """
    out = list(src)
    i, n = 0, len(src)

    def blank(lo: int, hi: int) -> None:
        for k in range(lo, min(hi, n)):
            if src[k] != '\n':
                out[k] = ' '

    while i < n:
        c = src[i]
        # 块注释（Kotlin 支持嵌套，必须按深度匹配，否则 KDoc 里的 `/*` 会提前收尾）
        if c == '/' and i + 1 < n and src[i + 1] == '*':
            depth = 1
            blank(i, i + 2); i += 2
            while i < n and depth:
                if src[i] == '/' and i + 1 < n and src[i + 1] == '*':
                    depth += 1; blank(i, i + 2); i += 2; continue
                if src[i] == '*' and i + 1 < n and src[i + 1] == '/':
                    depth -= 1; blank(i, i + 2); i += 2; continue
                if src[i] != '\n':
                    out[i] = ' '
                i += 1
            continue
        # 行注释
        if c == '/' and i + 1 < n and src[i + 1] == '/':
            j = src.find('\n', i)
            blank(i, n if j < 0 else j)
            i = n if j < 0 else j
            continue
        # 字符串（先于 `//` 之外的判断；`"content://"` 会在这里被整段吞掉，不会误判为注释）
        if c == '"':
            if src.startswith('"""', i):
                j = src.find('"""', i + 3)
                j = n if j < 0 else j + 3
            else:
                j = i + 1
                while j < n and src[j] != '"':
                    j += 2 if src[j] == '\\' else 1
                j = min(j + 1, n)
            blank(i, j)
            i = j
            continue
        i += 1
    return ''.join(out)


# ---------- 规则常量 ----------

# 宽泛捕获：会把 CancellationException 一起吞掉
BROAD_CATCH = re.compile(
    r'catch\s*\(\s*(?:_|[A-Za-z_]\w*)\s*:\s*(Exception|Throwable|Error|RuntimeException'
    r'|IllegalStateException|Exception\??)\s*\)'
)
CANCEL_CATCH = re.compile(r'catch\s*\(\s*(?:_|[A-Za-z_]\w*)\s*:\s*.*CancellationException')
ANY_CATCH = re.compile(r'\bcatch\s*\(')

# 协程上下文标志（用于 R1 的"文件级"判定与 R2 的"作用域级"判定）
CORO_SCOPE = re.compile(
    r'\bsuspend\s+fun\b'
    r'|\b(?:viewModelScope|lifecycleScope|coroutineScope|[A-Za-z]*[Ss]cope)\s*\.\s*(?:launch|async)\b'
    r'|\b(?:launch|async|runBlocking|withContext|coroutineScope|supervisorScope|produce|launchIn)\s*[(<{]'
)

FILE_HAS_CORO = re.compile(r'\bsuspend\b|\b(?:launch|async|runBlocking|withContext|coroutineScope)\b')

SUPPRESS = 'ci-ok'


# ---------- 逐行结构分析 ----------

class Line:
    __slots__ = ('no', 'text', 'depth', 'raw')

    def __init__(self, no: int, text: str, depth: int, raw: str):
        self.no, self.text, self.depth, self.raw = no, text, depth, raw


def analyze(path: Path) -> list[Line]:
    src = path.read_text(encoding='utf-8', errors='replace')
    code = strip_noise(src)
    raws = src.split('\n')
    lines, depth = [], 0
    for idx, text in enumerate(code.split('\n')):
        lines.append(Line(idx + 1, text.rstrip(), depth, raws[idx] if idx < len(raws) else ''))
        depth += text.count('{') - text.count('}')
    return lines


def in_coroutine_context(lines: list[Line], i: int) -> Line | None:
    """判断第 i 行是否处于**可能收到取消信号**的作用域内，返回该作用域行。

    逐层上溯"包裹当前行的作用域"：
    - 命中协程构造（suspend fun / launch / async / withContext / …）→ 会收到取消，返回该行；
    - 命中**非 suspend 的函数声明**（fun）→ 函数边界挡住取消传播，判定安全，返回 None；
    - 命中普通 lambda（.let { / .forEach { / try {）→ 不是边界，继续往外一层。
    """
    d = lines[i].depth
    for j in range(i - 1, -1, -1):
        if lines[j].depth < d and '{' in lines[j].text:
            t = lines[j].text
            if CORO_SCOPE.search(t):
                return lines[j]
            if re.search(r'\bfun\b', t):
                return None            # 非 suspend 函数 = 取消边界
            d = lines[j].depth         # 普通 lambda，继续上溯
    return None


def catch_group(lines: list[Line], i: int) -> list[Line]:
    """同一个 try 的所有 catch 子句。

    判定：同一 try 的 catch 之间夹着各自的**函数体**（更深的行），所以不能按"连续行"取，
    而要跳过更深的行、只在遇到更浅的行（说明 try 已结束）时停止。
    """
    grp = [lines[i]]
    d = lines[i].depth
    for j in range(i - 1, -1, -1):
        if lines[j].depth < d:
            break                                    # 更浅 = 所属 try 已结束
        if lines[j].depth == d and ANY_CATCH.search(lines[j].text):
            grp.append(lines[j])
    for j in range(i + 1, len(lines)):
        if lines[j].depth < d:
            break
        if lines[j].depth == d and ANY_CATCH.search(lines[j].text):
            grp.append(lines[j])
    return sorted(grp, key=lambda l: l.no)


def group_handles_cancel(lines: list[Line], grp: list[Line]) -> bool:
    if any(CANCEL_CATCH.search(l.text) for l in grp):
        return True
    # catch 体内首行调用 ensureActive() 也算处理（允许 3 行内的注释）。
    # ★ 不把 runCatchingCancellable 计入：它只能保证"它自己包住的那段"不吞取消，
    #   证明不了本 catch 捕获的异常类型排除了 CancellationException（会把 R1 变成常绿）。
    for l in grp:
        end = min(l.no + 4, len(lines))
        for k in range(l.no, end):
            if 'ensureActive' in lines[k].text:
                return True
    return False


def check(path: Path) -> list[str]:
    lines = analyze(path)
    problems: list[str] = []
    file_coro = any(FILE_HAS_CORO.search(l.text) for l in lines)

    for i, ln in enumerate(lines):
        if SUPPRESS in ln.raw:
            continue

        # ---- R1 ----
        if file_coro and BROAD_CATCH.search(ln.text):
            grp = catch_group(lines, i)
            if not group_handles_cancel(lines, grp):
                problems.append(
                    f'{path}:{ln.no}  [R1] catch 会吞掉取消信号：{ln.text.strip()}\n'
                    f'{" " * len(str(path))}        同一 try 需配 `catch (e: CancellationException) {{ throw e }}`，'
                    f'或在 catch 首行调用 ensureActive()。'
                )

        # ---- R2 ----
        m = re.search(r'\brunCatching\s*[{(\[]', ln.text)
        if m and 'runCatchingCancellable' not in ln.text:
            scope = in_coroutine_context(lines, i)
            if scope is not None and CORO_SCOPE.search(scope.text):
                problems.append(
                    f'{path}:{ln.no}  [R2] 协程上下文内的裸 runCatching 会吞掉取消：{ln.text.strip()}\n'
                    f'{" " * len(str(path))}        外层作用域 {scope.no}: {scope.text.strip()}\n'
                    f'{" " * len(str(path))}        改用 runCatchingCancellable（app/Cancellable.kt）。'
                )
    return problems


def main() -> int:
    roots = sys.argv[1:] or ['app', 'algorithm', 'data']
    files: list[Path] = []
    for r in roots:
        p = Path(r)
        if p.is_file():
            files.append(p)
        else:
            files += [f for f in p.rglob('*.kt') if '/build/' not in f.as_posix()]

    problems: list[str] = []
    for f in sorted(set(files)):
        problems += check(f)

    if problems:
        print('check-cancellation: 发现 %d 处会吞掉协程取消信号的写法\n' % len(problems))
        for p in problems:
            print(p)
        print('\n豁免：确认无挂起点时在行尾加 `// ci-ok: 原因`。')
        return 1
    print('check-cancellation: OK（已扫描 %d 个 Kotlin 文件，无吞取消模式）' % len(files))
    return 0


if __name__ == '__main__':
    sys.exit(main())
