package com.github.kr328.clash

/**
 * 从 Clash 订阅 YAML 里抽出 proxies 段的节点名。
 * 只统计真正的代理条目，不把后面的 proxy-groups（select/url-test）算进去。
 */
internal object SubscriptionYaml {
    private val proxyTypes = setOf(
        "ss", "ssr", "vmess", "vless", "trojan", "hysteria", "hysteria2",
        "tuic", "wireguard", "socks", "socks5", "http", "https", "snell",
        "anytls", "mieru", "shadowsocks"
    )

    private val namePattern = Regex(
        """(?:^|[{,\s])name\s*:\s*(\"[^\"]*\"|'[^']*'|[^\n,}]+)"""
    )
    private val typePattern = Regex(
        """(?:^|[{,\s])type\s*:\s*\"?([A-Za-z0-9_+-]+)\"?"""
    )

    fun countProxies(yaml: String): Int {
        val names = proxyNames(yaml)
        if (names.isNotEmpty()) return names.size
        return fallbackTypeCount(proxySection(yaml))
    }

    fun proxyNames(yaml: String): List<String> {
        val section = proxySection(yaml)
        if (section.isEmpty() || section.trim() == "[]") return emptyList()
        return namesInSection(section)
    }

    private fun proxySection(yaml: String): String {
        val text = yaml.replace("\r\n", "\n").replace('\r', '\n')
        val lines = text.split('\n')
        var sectionStart = -1
        for (i in lines.indices) {
            val trim = lines[i].trim()
            if (trim == "proxies:" || trim.startsWith("proxies:")) {
                sectionStart = i
                break
            }
        }
        if (sectionStart < 0) return ""
        val header = lines[sectionStart].trim().removePrefix("proxies:").trim()
        if (header == "[]") return "[]"
        if (header.startsWith("[")) return header
        val section = StringBuilder()
        for (i in (sectionStart + 1) until lines.size) {
            val raw = lines[i]
            val trim = raw.trim()
            if (trim.isEmpty()) {
                section.append('\n')
                continue
            }
            val indent = raw.indexOfFirst { !it.isWhitespace() }.let { if (it < 0) 0 else it }
            if (indent == 0 && !trim.startsWith("-") && !trim.startsWith("#")) break
            section.append(raw).append('\n')
        }
        return section.toString()
    }

    private fun namesInSection(section: String): List<String> {
        val names = mutableListOf<String>()
        val items = splitItems(section)
        for (item in items) {
            val name = namePattern.find(item)?.let { cleanScalar(it.groupValues[1]) } ?: continue
            if (name.isBlank()) continue
            val type = typePattern.find(item)?.groupValues?.get(1)?.lowercase()
            if (type != null && type !in proxyTypes) continue
            names.add(name)
        }
        return names
    }

    private fun splitItems(section: String): List<String> {
        val lines = section.split('\n')
        val items = mutableListOf<String>()
        val current = StringBuilder()
        var itemIndent = -1
        fun flush() {
            val text = current.toString().trim()
            if (text.isNotEmpty()) items.add(text)
            current.clear()
        }
        for (raw in lines) {
            val trim = raw.trim()
            if (trim.isEmpty() || trim.startsWith("#")) continue
            val indent = raw.indexOfFirst { !it.isWhitespace() }.let { if (it < 0) 0 else it }
            if (trim.startsWith("-") && (itemIndent < 0 || indent <= itemIndent)) {
                flush()
                itemIndent = indent
                current.append(trim.removePrefix("-").trim()).append('\n')
            } else if (current.isNotEmpty()) {
                current.append(trim).append('\n')
            }
        }
        flush()
        if (items.isEmpty() && section.contains("name")) {
            return section.split(Regex("""\}\s*,\s*\{""")).map { it.trim() }
        }
        return items
    }

    private fun cleanScalar(raw: String): String {
        var s = raw.trim()
        if ((s.startsWith("\"") && s.endsWith("\"") && s.length >= 2) ||
            (s.startsWith("'") && s.endsWith("'") && s.length >= 2)
        ) {
            s = s.substring(1, s.length - 1)
        }
        val hash = s.indexOf(" #")
        if (hash >= 0) s = s.substring(0, hash)
        return s.trim().trimEnd(',')
    }

    private fun fallbackTypeCount(section: String): Int {
        val types = listOf("vless", "trojan", "vmess", "hysteria2", "hysteria", "tuic", "ssr", "anytls", "ss")
        var n = 0
        for (t in types) {
            n += Regex("type:\\s*\"?$t\"?(?![A-Za-z0-9_-])").findAll(section).count()
        }
        return n
    }
}
