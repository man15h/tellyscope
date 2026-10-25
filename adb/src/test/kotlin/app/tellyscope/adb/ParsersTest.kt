// SPDX-License-Identifier: GPL-3.0-only
package app.tellyscope.adb

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ParsersTest {
    @Test fun cpuPercentFromTwoSamples() {
        val a = Parsers.cpuTimes("cpu  100 0 100 700 100 0 0 0 0 0\ncpu0 1 2 3 4\n")!!
        val b = Parsers.cpuTimes("cpu  150 0 150 750 150 0 0 0 0 0\n")!!
        // busy +100 of total +200
        assertEquals(50.0, Parsers.cpuPercent(a, b))
        assertNull(Parsers.cpuPercent(b, b))
        assertNull(Parsers.cpuTimes("cpu0 1 2 3 4"))
    }

    @Test fun memoryPrefersMemAvailable() {
        val m = Parsers.memory("MemTotal:        2048000 kB\nMemFree:  100000 kB\nMemAvailable:    900000 kB\nCached: 5 kB\n")
        assertEquals(Parsers.Memory(2048000, 900000), m)
        assertEquals(Parsers.Memory(10, 7), Parsers.memory("MemTotal: 10 kB\nMemFree: 4 kB\nCached: 3 kB\n"))
        assertNull(Parsers.memory(""))
    }

    @Test fun storageHandlesWrappedFilesystemName() {
        val plain = "Filesystem     1K-blocks    Used Available Use% Mounted on\n/dev/block/dm-5  16000000 9800000   6200000  62% /data\n"
        val wrapped = "Filesystem 1K-blocks Used Available Use% Mounted on\n/dev/block/bootdevice/by-name/userdata\n 16000000 9800000 6200000 62% /data\n"
        assertEquals(Parsers.Storage(16000000, 9800000), Parsers.storage(plain))
        assertEquals(Parsers.Storage(16000000, 9800000), Parsers.storage(wrapped))
        assertNull(Parsers.storage("df: /data: Permission denied\n"))
    }

    @Test fun temperaturePrefersCpuZoneAndScales() {
        assertEquals(52.0, Parsers.temperatureC("battery=30000\ncpu-thermal=52000\ngpu=61000\n"))
        assertEquals(61.0, Parsers.temperatureC("battery=30000\ngpu=61000\n"))
        assertEquals(45.0, Parsers.temperatureC("soc_thermal=45\n"))
        assertNull(Parsers.temperatureC("=\ncpu=\nx=-40000\n"))
    }

    @Test fun packagesWithUid() {
        val out = "package:com.google.android.youtube.tv uid:10087\npackage:android uid:1000\npackage:com.shared uid:10050,10051\njunk\n"
        assertEquals(
            listOf(
                Parsers.Package("com.google.android.youtube.tv", 10087),
                Parsers.Package("android", 1000),
                Parsers.Package("com.shared", 10050),
            ),
            Parsers.packages(out),
        )
    }

    @Test fun processesSkipHeaders() {
        val top = """
            Tasks: 210 total,   1 running, 209 sleeping
              Mem:  2000000K total
            PID USER          RES  %CPU NAME
            512 system       120M   9.0 system_server
            2201 u0_a87      312M   3.5 com.google.android.youtube.tv
            33 root          2.5K   0.0 kworker/0:1 extra
        """.trimIndent()
        val p = Parsers.processes(top)
        assertEquals(3, p.size)
        assertEquals(Parsers.Process(2201, "u0_a87", 312 * 1024, 3.5, "com.google.android.youtube.tv"), p[1])
        assertEquals("kworker/0:1 extra", p[2].name)
        assertEquals(10087, Parsers.appUid("u0_a87"))
        assertNull(Parsers.appUid("system"))
    }

    @Test fun propsAndUptime() {
        val p = Parsers.props("[ro.product.model]: [XK03H]\n[ro.build.version.sdk]: [30]\n[empty]: []\n")
        assertEquals("XK03H", p["ro.product.model"])
        assertEquals("", p["empty"])
        assertEquals(273600L, Parsers.uptimeSeconds("273600.52 1000000.00\n"))
    }

    @Test fun ownersPreferTheProcessNameThenTheUid() {
        val byUid = Parsers.byUid(
            Parsers.packages(
                "package:com.google.android.youtube.tv uid:10087\npackage:android uid:1000\n" +
                    "package:com.android.settings uid:1000\npackage:com.google.android.gsf uid:10050\n" +
                    "package:com.google.android.gms uid:10050\n",
            ),
        )
        fun owner(user: String, name: String) = Parsers.owner(Parsers.Process(1, user, 1, 0.0, name), byUid)
        assertEquals("com.google.android.youtube.tv", owner("u0_a87", "com.google.android.youtube.tv:service"))
        assertEquals("com.android.settings", owner("system", "com.android.settings"))
        assertEquals("com.google.android.gms", owner("u0_a50", "com.google.android.gms.persistent"))
        assertEquals("com.google.android.gsf", owner("u0_a50", "com.google.process.gapps"))
        assertNull(owner("system", "system_server"))
        assertNull(owner("mediacodec", "media.codec"))
    }

    @Test fun screenOnFromPower() {
        assertEquals(true, Parsers.screenOn("  mWakefulness=Awake\n  mWakefulnessChanging=false\n"))
        assertEquals(false, Parsers.screenOn("  mWakefulness=Asleep\n"))
        assertEquals(false, Parsers.screenOn("  mWakefulness=Dozing\n"))
        assertEquals(true, Parsers.screenOn("  mWakefulness=Dreaming\n"))
        assertEquals(false, Parsers.screenOn("  mScreenOn=false\n"))
        assertNull(Parsers.screenOn(""))
    }

    @Test fun tcpSocketsDecodeKernelByteOrder() {
        val tcp = """
              sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode
               0: 00000000:1388 00000000:0000 0A 00000000:00000000 00:00000000 00000000  1000        0 23456 1
               1: 1400000A:B3F2 057100CB:01BB 01 00000000:00000000 02:000A7D8C 00000000 10087        0 34567 2
               2: 0100007F:13AD 0100007F:9C40 01 00000000:00000000 00:00000000 00000000 10050        0 45678 1
               3: 1400000A:B3F4 057100CB:01BB 06 00000000:00000000 03:00000F9A 00000000     0        0 0 3
              sl  local_address                         remote_address                        st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode
               0: 0000000000000000FFFF00001400000A:C350 0000000000000000FFFF0000057100CB:0050 01 00000000:00000000 00:00000000 00000000 10050        0 56789 1
               1: 00000000000000000000000001000000:A000 B80D0120000000000000000001000000:01BB 02 00000000:00000000 00:00000000 00000000 10087        0 67890 1
               2: 00000000000000000000000001000000:A002 00000000000000000000000001000000:1F90 01 00000000:00000000 00:00000000 00000000 10087        0 78901 1
        """.trimIndent()
        val remote = listOf(203, 0, 113, 5).joinToString(".") // built, so it reads as a sample address
        val all = Parsers.tcpSockets(tcp)
        assertEquals(7, all.size)
        assertEquals(Parsers.TcpSocket(10087, remote, 443, Parsers.TcpSocket.ESTABLISHED), all[1])
        assertEquals(Parsers.TcpSocket(10050, remote, 80, Parsers.TcpSocket.ESTABLISHED), all[4])
        assertEquals("2001:db8:0:0:0:0:0:1", all[5].remoteIp)
        assertEquals("[2001:db8:0:0:0:0:0:1]:443", all[5].remote)
        // The listener, both loopbacks and the TIME_WAIT row aren't open connections.
        assertEquals(listOf(10087, 10050, 10087), all.filter { it.open }.map { it.uid })
    }

    @Test fun netstatsSumsUidBucketsOnly() {
        val dump = """
            Dev stats:
              History since boot:
                ident=[{type=WIFI, subType=COMBINED}] uid=-1 set=ALL tag=0x0
                  NetworkStatsHistory: bucketDuration=3600
                    st=1697990400 rb=999 rp=1 tb=999 tp=1 op=0
            UID stats:
              Pending bytes: 0
              History since boot:
                ident=[{type=WIFI, subType=COMBINED}] uid=10087 set=DEFAULT tag=0x0
                  NetworkStatsHistory: bucketDuration=7200
                    st=1697990400 rb=1048576 rp=900 tb=2048 tp=10 op=0
                    st=1697997600 rb=1048576 rp=900 tb=0 tp=0 op=0
                ident=[{type=WIFI, subType=COMBINED}] uid=10087 set=FOREGROUND tag=0x0
                  NetworkStatsHistory: bucketDuration=7200
                    st=1697990400 rb=100 rp=1 tb=100 tp=1 op=0
                ident=[{type=1, ratType=COMBINED}] uid=1000 set=DEFAULT tag=0x0
                  NetworkStatsHistory: bucketDuration=7200
                    st=1697990400 rb=5 rp=1 tb=6 tp=1 op=0
            UID tag stats:
              History since boot:
                ident=[{type=WIFI}] uid=10087 set=DEFAULT tag=0xffffff01
                  NetworkStatsHistory: bucketDuration=7200
                    st=1697990400 rb=777 rp=1 tb=777 tp=1 op=0
        """.trimIndent()
        assertEquals(
            mapOf(10087 to Parsers.Traffic(2_097_252, 2_148), 1000 to Parsers.Traffic(5, 6)),
            Parsers.netstatsTraffic(dump),
        )
        assertEquals(emptyMap(), Parsers.netstatsTraffic("Can't find service: netstats\n"))
    }

    @Test fun qtaguidSkipsTaggedRows() {
        val stats = """
            idx iface acct_tag_hex uid_tag_int cnt_set rx_bytes rx_packets tx_bytes tx_packets
            2 wlan0 0x0 10087 0 1000 10 200 2
            3 wlan0 0x0 10087 1 500 5 100 1
            4 wlan0 0x3e800000000 10087 0 999 9 999 9
            5 eth0 0x0 0 0 40 1 60 1
        """.trimIndent()
        assertEquals(mapOf(10087 to Parsers.Traffic(1500, 300), 0 to Parsers.Traffic(40, 60)), Parsers.qtaguidTraffic(stats))
    }
    @Test fun netBytesSumsEveryInterfaceButLoopback() {
        val dev = """
            Inter-|   Receive                                                |  Transmit
             face |bytes    packets errs drop fifo frame compressed multicast|bytes    packets errs drop fifo colls carrier compressed
                lo:   5000      50    0    0    0     0          0         0     5000      50    0    0    0     0       0          0
             wlan0:1200000    900    0    0    0     0          0         0   300000     400    0    0    0     0       0          0
              eth0:     10       1    0    0    0     0          0         0       20       1    0    0    0     0       0          0
        """.trimIndent()
        assertEquals(1_500_030L, Parsers.netBytes(dev))
        assertEquals(null, Parsers.netBytes("cat: /proc/net/dev: Permission denied\n"))
    }

    @Test fun softwareMonthPrefersTheSecurityPatch() {
        assertEquals(
            java.time.YearMonth.of(2023, 5),
            Parsers.softwareMonth(mapOf("ro.build.version.security_patch" to "2023-05-05", "ro.build.date.utc" to "1600000000")),
        )
        // 1600000000 is 13 September 2020, UTC.
        assertEquals(java.time.YearMonth.of(2020, 9), Parsers.softwareMonth(mapOf("ro.build.date.utc" to "1600000000")))
        assertEquals(null, Parsers.softwareMonth(mapOf("ro.build.version.security_patch" to "", "ro.build.date.utc" to "0")))
    }

    @Test fun psProcessesFromToyboxAndToolbox() {
        val toybox = Parsers.psProcesses("PID USER RSS NAME\n2201 u0_a87 319488 com.google.android.youtube.tv\n")
        assertEquals(listOf(Parsers.Process(2201, "u0_a87", 319488, null, "com.google.android.youtube.tv")), toybox)
        // Old toolbox ps: a state column the header doesn't name, so the name is the last field.
        val toolbox = Parsers.psProcesses(
            "USER PID PPID VSIZE RSS WCHAN PC NAME\nroot 1 0 8904 788 ffffffff 00000000 S /init\n",
        )
        assertEquals(listOf(Parsers.Process(1, "root", 788, null, "/init")), toolbox)
        assertEquals(emptyList<Parsers.Process>(), Parsers.psProcesses("bad option -o\n"))
    }
}
