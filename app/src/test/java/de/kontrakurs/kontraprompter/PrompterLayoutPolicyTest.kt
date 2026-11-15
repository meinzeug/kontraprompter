package de.kontrakurs.kontraprompter

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrompterLayoutPolicyTest {
    @Test fun compactLayoutRequiresWideShortSpaceAndNormalText() {
        assertTrue(useWidePrompterLayout(720f, 360f, 1f, false))
        assertTrue(useWidePrompterLayout(900f, 500f, 1.1f, false))
        assertFalse(useWidePrompterLayout(719f, 360f, 1f, false))
        assertFalse(useWidePrompterLayout(720f, 501f, 1f, false))
        assertFalse(useWidePrompterLayout(720f, 720f, 1f, false))
        assertFalse(useWidePrompterLayout(360f, 720f, 1f, false))
        assertFalse(useWidePrompterLayout(800f, 360f, 1.11f, false))
    }

    @Test fun keyboardRemoteAndInvalidDimensionsUseExistingLayout() {
        assertFalse(useWidePrompterLayout(800f, 360f, 1f, true))
        assertFalse(useWidePrompterLayout(Float.POSITIVE_INFINITY, 360f, 1f, false))
        assertFalse(useWidePrompterLayout(800f, 0f, 1f, false))
        assertFalse(useWidePrompterLayout(800f, 360f, Float.NaN, false))
    }
}
