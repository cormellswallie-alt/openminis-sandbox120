package com.openminis.app.sandbox

import java.io.File

/** One best-effort /proc walk; never caches live pids between operations. */
internal object ProcSnapshot {
    fun children(procRoot: File): Map<Int, List<Int>> {
        val children = HashMap<Int, MutableList<Int>>()
        procRoot.list()?.forEach { name ->
            val pid = name.toIntOrNull() ?: return@forEach
            val stat = runCatching { File(procRoot, "$name/stat").readText() }.getOrNull()
                ?: return@forEach
            val parent = parseParentPid(stat) ?: return@forEach
            children.getOrPut(parent) { ArrayList() }.add(pid)
        }
        return children
    }

    /** Postorder, so children are signalled before their parents. Root is excluded. */
    fun descendants(children: Map<Int, List<Int>>, root: Int, maxDepth: Int = Int.MAX_VALUE): List<Int> {
        val result = ArrayList<Int>()
        val seen = HashSet<Int>()
        seen.add(root)
        val stack = ArrayDeque<Frame>()
        stack.addLast(Frame(root, 0, false))
        while (stack.isNotEmpty()) {
            val frame = stack.removeLast()
            if (frame.expanded) {
                if (frame.pid != root) result.add(frame.pid)
                continue
            }
            stack.addLast(Frame(frame.pid, frame.depth, true))
            if (frame.depth >= maxDepth) continue
            for (child in children[frame.pid].orEmpty().asReversed()) {
                if (seen.add(child)) stack.addLast(Frame(child, frame.depth + 1, false))
            }
        }
        return result
    }

    private data class Frame(val pid: Int, val depth: Int, val expanded: Boolean)

    /** comm may contain spaces and ')'; only state and ppid after the last ')' matter. */
    fun parseParentPid(stat: String): Int? {
        val close = stat.lastIndexOf(')')
        if (close < 0) return null
        var start = close + 1
        while (start < stat.length && stat[start].isWhitespace()) start++
        while (start < stat.length && !stat[start].isWhitespace()) start++
        while (start < stat.length && stat[start].isWhitespace()) start++
        var end = start
        while (end < stat.length && !stat[end].isWhitespace()) end++
        return stat.substring(start, end).toIntOrNull()
    }
}
