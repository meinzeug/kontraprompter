package de.kontrakurs.kontraprompter

import org.junit.Assert.*
import org.junit.Test

class RemotePrompterPolicyTest {
    @Test fun `only finite values within actual control ranges are accepted`() {
        assertEquals(5f, remotePrompterValue("set_speed", 5.0)!!, 0f)
        assertEquals(160f, remotePrompterValue("set_speed", 160.0)!!, 0f)
        assertEquals(20f, remotePrompterValue("set_font_size", 20.0)!!, 0f)
        assertEquals(100f, remotePrompterValue("set_font_size", 100.0)!!, 0f)
        for (value in listOf(null, Double.NaN, Double.POSITIVE_INFINITY, 4.0, 161.0)) assertNull(remotePrompterValue("set_speed", value))
        for (value in listOf(null, Double.NaN, Double.NEGATIVE_INFINITY, 19.0, 101.0)) assertNull(remotePrompterValue("set_font_size", value))
        assertNull(remotePrompterValue("play", 35.0))
    }
}
