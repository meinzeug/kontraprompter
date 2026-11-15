package de.kontrakurs.kontraprompter

import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsModifier
import androidx.compose.ui.semantics.SemanticsProperties
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StudioAnnouncementTest {
    private fun liveRegion(modifier: Modifier): LiveRegionMode? = modifier.foldIn(null) { result, element ->
        if (element is SemanticsModifier && element.semanticsConfiguration.contains(SemanticsProperties.LiveRegion))
            element.semanticsConfiguration[SemanticsProperties.LiveRegion]
        else result
    }

    @Test fun staticMessageDoesNotAnnounceByDefault() {
        assertNull(liveRegion(studioAnnouncementModifier()))
        assertNull(liveRegion(studioAnnouncementModifier(false)))
    }

    @Test fun explicitlyDynamicMessageUsesPoliteAnnouncement() {
        assertEquals(LiveRegionMode.Polite, liveRegion(studioAnnouncementModifier(true)))
    }
}
