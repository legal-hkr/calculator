package sh.hnet.calculator.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupTest {
    private val sample = BackupFile(
        appVersion = "0.1.0",
        appVersionCode = 1,
        createdAt = "2026-11-15T10:00:00Z",
        settings = BackupSettings(decimalPlaces = "2", scientificNotation = "ALWAYS", thousandsSeparator = false, vibrateOnKey = true, angleUnit = "RADIANS"),
        programmer = BackupProgrammer(8, false),
        converterPairs = listOf(BackupPair("LENGTH", "inch", "centimeter")),
        history = listOf(
            BackupEntry("CALCULATOR", "d2 oPLUS d2", "q4/1", 1),
            BackupEntry("PROGRAMMER", "d1 dF", "q31/1", 2, "HEX", 8, false),
        ),
    )

    @Test fun goesThereAndBack() {
        val check = Backup.check(Backup.encode(sample))
        assertTrue(check is BackupCheck.Valid)
        assertEquals(sample, (check as BackupCheck.Valid).backup)
    }

    @Test fun refusesWhatIsNotOurs() {
        assertTrue(Backup.check("not json") is BackupCheck.NotOurs)
        assertTrue(Backup.check("""{"format":"sh.hnet.news.backup","formatVersion":1}""") is BackupCheck.NotOurs)
        assertEquals(BackupCheck.Newer(2), Backup.check("""{"format":"sh.hnet.calculator.backup","formatVersion":2}"""))
    }

    @Test fun readsAnOlderOrSmallerFile() {
        // Every section may be absent
        val check = Backup.check("""{"format":"sh.hnet.calculator.backup","formatVersion":1,"appVersion":"0.1.0","appVersionCode":1,"createdAt":"x","unknown":3}""")
        assertTrue(check is BackupCheck.Valid)
        assertEquals(0, (check as BackupCheck.Valid).backup.history.size)
    }
}
