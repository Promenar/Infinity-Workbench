"""只读状态的精确行差异计数，不保留补丁或编辑路径。"""


def _lines(data):
    # Git 只按 LF 分行；末尾是否有 LF 是最后一行内容的一部分。
    if not data:
        return []
    parts = data.split(b"\n")
    return [part + b"\n" for part in parts[:-1]] + ([parts[-1]] if parts[-1] else [])


def numstat(old, new, spend_work):
    """Myers 最短编辑路径等价于行 LCS；调用方逐步计费，预算不足时抛出异常。"""
    a, b = _lines(old), _lines(new)
    n, m = len(a), len(b)
    if not n or not m:
        return m, n
    frontier = {1: 0}
    for distance in range(n + m + 1):
        for diagonal in range(-distance, distance + 1, 2):
            spend_work()
            if diagonal == -distance or (diagonal != distance and frontier[diagonal - 1] < frontier[diagonal + 1]):
                x = frontier[diagonal + 1]
            else:
                x = frontier[diagonal - 1] + 1
            y = x - diagonal
            while x < n and y < m and a[x] == b[y]:
                spend_work()
                x, y = x + 1, y + 1
            frontier[diagonal] = x
            if x >= n and y >= m:
                removed = (distance + n - m) // 2
                return distance - removed, removed
