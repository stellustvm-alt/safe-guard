package com.spirit.focusguard

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.Executors

/**
 * Local DNS filter. Only DNS traffic enters this "VPN" (nothing leaves your phone to a
 * third-party VPN). Every website lookup is checked against your blocklist, then
 * forwarded to Cloudflare for Families (1.1.1.3), which blocks adult sites and malware.
 */
class DnsVpnService : VpnService() {

    companion object {
        private const val VPN_DNS = "10.111.222.1"
        private val UPSTREAM: InetAddress = InetAddress.getByAddress(byteArrayOf(1, 1, 1, 3))

        /** Always blocked: encrypted-DNS services that browsers could use to bypass the filter. */
        private val ALWAYS_BLOCK = setOf(
            "use-application-dns.net",   // tells Firefox not to use its own DNS
            "dns.google", "cloudflare-dns.com", "one.one.one.one", "dns.quad9.net",
            "doh.opendns.com", "dns.adguard.com", "dns.adguard-dns.com", "dns.nextdns.io",
            "doh.cleanbrowsing.org", "mask.icloud.com", "mask-h2.icloud.com",
        )

        @Volatile private var blocked: Set<String> = ALWAYS_BLOCK

        fun reload(c: Context) { blocked = Store.sites(c) + ALWAYS_BLOCK }

        fun isBlocked(name: String): Boolean {
            var d = name.lowercase().trimEnd('.')
            while (true) {
                if (d in blocked) return true
                val i = d.indexOf('.')
                if (i < 0) return false
                d = d.substring(i + 1)    // check parent domain too: a.b.example.com -> example.com
            }
        }
    }

    private var tun: ParcelFileDescriptor? = null
    private var reader: Thread? = null
    private val pool = Executors.newFixedThreadPool(8)
    private val writeLock = Any()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (tun == null) establish()
        return START_STICKY
    }

    private fun establish() {
        reload(this)
        val fd = Builder()
            .setSession("FocusGuard filter")
            .addAddress("10.111.222.2", 32)
            .addRoute(VPN_DNS, 32)          // only DNS goes through the filter
            .addDnsServer(VPN_DNS)
            .setBlocking(true)
            .establish() ?: return
        tun = fd
        val input = FileInputStream(fd.fileDescriptor)
        val output = FileOutputStream(fd.fileDescriptor)
        reader = Thread {
            val buf = ByteArray(32767)
            try {
                while (!Thread.currentThread().isInterrupted) {
                    val n = input.read(buf)
                    if (n > 0) handle(buf.copyOf(n), output)
                }
            } catch (_: Exception) { }
        }.apply { start() }
    }

    private fun handle(p: ByteArray, out: FileOutputStream) {
        if (p.size < 28 || (p[0].toInt() shr 4) != 4 || p[9].toInt() != 17) return  // IPv4 + UDP only
        val ihl = (p[0].toInt() and 0x0F) * 4
        if (p.size < ihl + 8 + 12 || u16(p, ihl + 2) != 53) return
        val dns = p.copyOfRange(ihl + 8, p.size)
        val (name, qEnd) = parseQuestion(dns) ?: return

        if (isBlocked(name)) {
            reply(out, p, ihl, blockedAnswer(dns, qEnd))
            return
        }
        pool.execute {
            try {
                DatagramSocket().use { s ->
                    protect(s)
                    s.soTimeout = 5000
                    s.send(DatagramPacket(dns, dns.size, UPSTREAM, 53))
                    val r = ByteArray(4096)
                    val rp = DatagramPacket(r, r.size)
                    s.receive(rp)
                    reply(out, p, ihl, r.copyOf(rp.length))
                }
            } catch (_: Exception) { }
        }
    }

    /** Returns (domain name, end offset of question section). */
    private fun parseQuestion(d: ByteArray): Pair<String, Int>? {
        var i = 12
        val sb = StringBuilder()
        while (i < d.size) {
            val len = d[i].toInt() and 0xFF
            if (len == 0) { i++; break }
            if (len and 0xC0 != 0 || i + 1 + len > d.size) return null
            if (sb.isNotEmpty()) sb.append('.')
            sb.append(String(d, i + 1, len, Charsets.US_ASCII))
            i += 1 + len
        }
        val end = i + 4  // QTYPE + QCLASS
        return if (end <= d.size && sb.isNotEmpty()) sb.toString() to end else null
    }

    /** NXDOMAIN reply = "this site does not exist". */
    private fun blockedAnswer(q: ByteArray, qEnd: Int): ByteArray {
        val r = q.copyOf(qEnd)
        r[2] = (q[2].toInt() or 0x80).toByte()  // QR = response, keep opcode + RD
        r[3] = 0x83.toByte()                    // RA = 1, RCODE = 3 (NXDOMAIN)
        for (k in 6..11) r[k] = 0               // no answer / authority / additional records
        return r
    }

    /** Wrap a DNS payload in IPv4/UDP headers addressed back to the app that asked. */
    private fun reply(out: FileOutputStream, req: ByteArray, ihl: Int, payload: ByteArray) {
        val total = 28 + payload.size
        val b = ByteArray(total)
        b[0] = 0x45; put16(b, 2, total); put16(b, 6, 0x4000); b[8] = 64; b[9] = 17
        System.arraycopy(req, 16, b, 12, 4)    // src = original dst
        System.arraycopy(req, 12, b, 16, 4)    // dst = original src
        put16(b, 10, checksum(b, 20))
        put16(b, 20, u16(req, ihl + 2))        // src port 53
        put16(b, 22, u16(req, ihl))            // dst port = app's port
        put16(b, 24, 8 + payload.size)         // UDP checksum left 0 (optional on IPv4)
        System.arraycopy(payload, 0, b, 28, payload.size)
        synchronized(writeLock) { out.write(b) }
    }

    private fun u16(b: ByteArray, o: Int) = ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)
    private fun put16(b: ByteArray, o: Int, v: Int) { b[o] = (v shr 8).toByte(); b[o + 1] = v.toByte() }
    private fun checksum(b: ByteArray, len: Int): Int {
        var sum = 0
        var i = 0
        while (i < len) { sum += u16(b, i); i += 2 }
        while (sum shr 16 != 0) sum = (sum and 0xFFFF) + (sum shr 16)
        return sum.inv() and 0xFFFF
    }

    private fun shutdown() {
        reader?.interrupt()
        runCatching { tun?.close() }
        tun = null
    }

    override fun onRevoke() { shutdown(); super.onRevoke() }
    override fun onDestroy() { shutdown(); pool.shutdownNow(); super.onDestroy() }
}
