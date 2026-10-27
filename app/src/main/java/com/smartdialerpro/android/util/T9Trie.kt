package com.smartdialerpro.android.util

import com.smartdialerpro.android.data.RecentCall

class T9TrieNode {
    val children = arrayOfNulls<T9TrieNode>(10)
    val calls = ArrayList<RecentCall>()
}

class T9Trie {
    private val root = T9TrieNode()
    private val allIndexedCalls = ArrayList<Pair<String, RecentCall>>()

    @Synchronized
    fun clear() {
        for (i in 0 until 10) root.children[i] = null
        root.calls.clear()
        allIndexedCalls.clear()
    }

    @Synchronized
    fun insert(digits: String, nameLower: String, call: RecentCall) {
        if (digits.isBlank() && nameLower.isBlank()) return

        // Store for instant text-name substring search
        if (!allIndexedCalls.any { it.second.id == call.id }) {
            allIndexedCalls.add(nameLower to call)
        }

        // Index digits and T9 into Trie node branches
        if (digits.isNotBlank()) {
            val len = digits.length
            for (start in 0 until len) {
                var current = root
                val end = minOf(start + 12, len)
                for (i in start until end) {
                    val digit = digits[i]
                    if (digit !in '0'..'9') break
                    val idx = digit - '0'
                    if (current.children[idx] == null) {
                        current.children[idx] = T9TrieNode()
                    }
                    current = current.children[idx]!!
                    if (!current.calls.any { it.id == call.id }) {
                        current.calls.add(call)
                    }
                }
            }
        }
    }

    /**
     * Unified Search:
     * - If query has letters: Instantly searches caller names via substring match.
     * - If query is numbers: Uses Trie for 0ms T9 name and phone number matches.
     */
    @Synchronized
    fun search(query: String, maxResults: Int = 15): List<RecentCall> {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return emptyList()

        val hasLetters = trimmed.any { it.isLetter() }

        if (hasLetters) {
            val queryLower = trimmed.lowercase()
            return allIndexedCalls
                .filter { it.first.contains(queryLower) }
                .map { it.second }
                .distinctBy { if (it.number.length >= 7) it.number.takeLast(10) else it.number }
                .take(maxResults)
        }

        // Pure digits/T9 search
        val digits = trimmed.filter { it in '0'..'9' }
        if (digits.isBlank()) return emptyList()

        var current = root
        for (d in digits) {
            val idx = d - '0'
            current = current.children[idx] ?: return emptyList()
        }

        return current.calls
            .distinctBy { if (it.number.length >= 7) it.number.takeLast(10) else it.number }
            .take(maxResults)
    }
}
