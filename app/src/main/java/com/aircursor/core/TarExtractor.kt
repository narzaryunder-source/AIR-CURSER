package com.aircursor.core

import java.io.IOException
import java.io.InputStream
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission

/**
 * Unpacks a tar stream (ustar + GNU long names + pax) into [root] without ever writing
 * outside it: ".." and absolute names are cleaned, and an entry whose parent folder is a
 * symlink is skipped, so a link inside the archive can never redirect a write to the phone's
 * own files. Device files and FIFOs are skipped (not needed for proot).
 */
class TarExtractor(private val root: Path) {
    var files = 0
        private set
    var skipped = 0
        private set

    private val dirModes = ArrayList<Pair<Path, Int>>()

    fun extract(input: InputStream) {
        Files.createDirectories(root)
        val header = ByteArray(BLOCK)
        var longName: String? = null
        var longLink: String? = null
        var paxPath: String? = null
        var paxLink: String? = null
        var paxSize: Long? = null
        while (true) {
            if (!readBlock(input, header)) break
            if (header.all { it.toInt() == 0 }) break // end of archive
            if (!checksumOk(header)) throw IOException("Broken tar header (download damaged?)")
            val type = header[156].toInt().toChar()
            var size = paxSize ?: parseNumber(header, 124, 12)
            if (type == 'L' || type == 'K' || type == 'x' || type == 'g') {
                val data = readData(input, size)
                when (type) {
                    'L' -> longName = cString(data)
                    'K' -> longLink = cString(data)
                    'x' -> parsePax(data).let { pax ->
                        paxPath = pax["path"]
                        paxLink = pax["linkpath"]
                        paxSize = pax["size"]?.toLongOrNull()
                    }
                }
                continue
            }
            val name = paxPath ?: longName ?: fullName(header)
            val link = paxLink ?: longLink ?: field(header, 157, 100)
            if (type == '1' || type == '2') size = 0 // links carry no data
            longName = null
            longLink = null
            paxPath = null
            paxLink = null
            paxSize = null
            val mode = parseNumber(header, 100, 8).toInt()
            handleEntry(type, name, link, mode, size, input)
            skipPadding(input, size)
        }
        // Folder permissions last, so read-only folders do not block their own contents.
        for ((dir, m) in dirModes.asReversed()) setMode(dir, m or 0b111_000_000)
    }

    private fun handleEntry(type: Char, name: String, link: String, mode: Int, size: Long, input: InputStream) {
        val rel = clean(name)
        if (rel == null) {
            skipBytes(input, size)
            if (name.trim('/', '.').isNotEmpty()) skipped++
            return
        }
        val target = safeTarget(rel)
        if (target == null) {
            skipBytes(input, size)
            skipped++
            return
        }
        when (type) {
            '5' -> {
                if (Files.isSymbolicLink(target)) Files.delete(target)
                if (!Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(target)
                dirModes += target to mode
            }
            '2' -> {
                replace(target)
                Files.createSymbolicLink(target, Paths.get(link))
                files++
            }
            '1' -> {
                val src = clean(link)?.let { root.resolve(it) }
                replace(target)
                when {
                    src == null || !Files.exists(src, LinkOption.NOFOLLOW_LINKS) -> skipped++
                    Files.isSymbolicLink(src) -> Files.createSymbolicLink(target, Files.readSymbolicLink(src))
                    else -> try {
                        Files.createLink(target, src)
                    } catch (e: Exception) {
                        Files.copy(src, target, LinkOption.NOFOLLOW_LINKS)
                    }
                }
                files++
            }
            '0', '\u0000', '7' -> {
                replace(target)
                Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { out ->
                    copyBytes(input, out, size)
                }
                setMode(target, mode or 0b110_000_000)
                files++
            }
            else -> { // char/block devices, FIFOs: not needed inside proot
                skipBytes(input, size)
                skipped++
            }
        }
    }

    /** Creates missing parent folders; null if any parent is a symlink or a file (escape attempt). */
    private fun safeTarget(rel: String): Path? {
        val parts = rel.split('/')
        var cur = root
        for (i in 0 until parts.size - 1) {
            cur = cur.resolve(parts[i])
            when {
                Files.isSymbolicLink(cur) -> return null
                !Files.exists(cur, LinkOption.NOFOLLOW_LINKS) -> {
                    Files.createDirectory(cur)
                    dirModes += cur to 0b111_101_101
                }
                !Files.isDirectory(cur, LinkOption.NOFOLLOW_LINKS) -> return null
            }
        }
        return cur.resolve(parts.last())
    }

    private fun replace(target: Path) {
        if (Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
            try {
                Files.delete(target) // only empty folders are replaced
            } catch (e: Exception) {
                throw FileAlreadyExistsException(target.toString())
            }
        } else {
            Files.deleteIfExists(target) // deletes a symlink itself, never what it points to
        }
    }

    private fun setMode(p: Path, mode: Int) {
        try {
            val perms = HashSet<PosixFilePermission>()
            val all = PosixFilePermission.values() // OWNER_READ .. OTHERS_EXECUTE, bit 8 .. 0
            for (i in all.indices) if (mode and (1 shl (8 - i)) != 0) perms += all[i]
            Files.setPosixFilePermissions(p, perms)
        } catch (e: Exception) {
            // File systems without permissions: ignore.
        }
    }

    companion object {
        private const val BLOCK = 512

        /** Archive name -> safe relative path, or null for the root entry / names with "..". */
        fun clean(name: String): String? {
            val parts = name.split('/').filter { it.isNotEmpty() && it != "." }
            if (parts.isEmpty() || parts.any { it == ".." }) return null
            return parts.joinToString("/")
        }

        private fun readBlock(input: InputStream, buf: ByteArray): Boolean {
            var n = 0
            while (n < buf.size) {
                val r = input.read(buf, n, buf.size - n)
                if (r < 0) {
                    if (n == 0) return false
                    throw IOException("Archive ended early (download incomplete?)")
                }
                n += r
            }
            return true
        }

        private fun readData(input: InputStream, size: Long): ByteArray {
            if (size > 16L * 1024 * 1024) throw IOException("Header too big")
            val buf = ByteArray(size.toInt())
            var n = 0
            while (n < buf.size) {
                val r = input.read(buf, n, buf.size - n)
                if (r < 0) throw IOException("Archive ended early")
                n += r
            }
            skipPadding(input, size)
            return buf
        }

        private fun copyBytes(input: InputStream, out: java.io.OutputStream, size: Long) {
            val buf = ByteArray(64 * 1024)
            var left = size
            while (left > 0) {
                val r = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (r < 0) throw IOException("Archive ended early")
                out.write(buf, 0, r)
                left -= r
            }
        }

        private fun skipBytes(input: InputStream, size: Long) {
            val buf = ByteArray(8192)
            var left = size
            while (left > 0) {
                val r = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (r < 0) throw IOException("Archive ended early")
                left -= r
            }
        }

        private fun skipPadding(input: InputStream, size: Long) {
            val pad = (BLOCK - (size % BLOCK)) % BLOCK
            skipBytes(input, pad)
        }

        private fun field(h: ByteArray, off: Int, len: Int): String {
            var end = off
            while (end < off + len && h[end].toInt() != 0) end++
            return String(h, off, end - off, Charsets.UTF_8)
        }

        private fun cString(data: ByteArray): String {
            val end = data.indexOf(0.toByte()).let { if (it < 0) data.size else it }
            return String(data, 0, end, Charsets.UTF_8)
        }

        private fun fullName(h: ByteArray): String {
            val name = field(h, 0, 100)
            val magic = field(h, 257, 6)
            val prefix = if (magic.startsWith("ustar")) field(h, 345, 155) else ""
            return if (prefix.isNotEmpty()) "$prefix/$name" else name
        }

        /** Octal text, or GNU base-256 when the first byte has the high bit set. */
        fun parseNumber(h: ByteArray, off: Int, len: Int): Long {
            if (h[off].toInt() and 0x80 != 0) {
                var v = (h[off].toLong() and 0x7F)
                for (i in off + 1 until off + len) v = (v shl 8) or (h[i].toLong() and 0xFF)
                return v
            }
            var v = 0L
            var started = false
            for (i in off until off + len) {
                val c = h[i].toInt().toChar()
                when {
                    c in '0'..'7' -> {
                        v = v * 8 + (c - '0')
                        started = true
                    }
                    !started && c == ' ' -> Unit // leading spaces
                    else -> break // NUL or space after the digits
                }
            }
            return v
        }

        private fun checksumOk(h: ByteArray): Boolean {
            val stored = parseNumber(h, 148, 8)
            var sum = 0L
            for (i in h.indices) sum += if (i in 148 until 156) 32 else (h[i].toLong() and 0xFF)
            return stored == sum
        }

        private fun parsePax(data: ByteArray): Map<String, String> {
            val out = HashMap<String, String>()
            var i = 0
            while (i < data.size) {
                var sp = i
                while (sp < data.size && data[sp] != ' '.code.toByte()) sp++
                val len = String(data, i, sp - i, Charsets.US_ASCII).toIntOrNull() ?: break
                if (len <= 0 || i + len > data.size) break
                val rec = String(data, sp + 1, i + len - sp - 2, Charsets.UTF_8) // without trailing \n
                val eq = rec.indexOf('=')
                if (eq > 0) out[rec.substring(0, eq)] = rec.substring(eq + 1)
                i += len
            }
            return out
        }
    }
}

/** Deleting Cloud PC files safely: never follows a symlink, never leaves [mustBeInside]. */
object SafeFiles {
    fun deleteTree(dir: Path, mustBeInside: Path) {
        val base = mustBeInside.toAbsolutePath().normalize()
        val d = dir.toAbsolutePath().normalize()
        require(d.startsWith(base) && d != base) { "Refusing to delete outside $base" }
        if (!Files.exists(d, LinkOption.NOFOLLOW_LINKS)) return
        if (Files.isSymbolicLink(d)) {
            Files.delete(d)
            return
        }
        // walkFileTree without FOLLOW_LINKS reports symlinks as plain entries: only the link is removed.
        Files.walkFileTree(d, object : java.nio.file.SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(p: Path, a: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
                // Make sure we may delete inside folders that were read-only in the archive.
                try {
                    Files.setPosixFilePermissions(p, Files.getPosixFilePermissions(p, LinkOption.NOFOLLOW_LINKS) +
                        setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE))
                } catch (e: Exception) {
                }
                return java.nio.file.FileVisitResult.CONTINUE
            }

            override fun visitFile(p: Path, a: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
                Files.delete(p)
                return java.nio.file.FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(p: Path, e: IOException): java.nio.file.FileVisitResult {
                Files.deleteIfExists(p)
                return java.nio.file.FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(p: Path, e: IOException?): java.nio.file.FileVisitResult {
                Files.delete(p)
                return java.nio.file.FileVisitResult.CONTINUE
            }
        })
    }
}
