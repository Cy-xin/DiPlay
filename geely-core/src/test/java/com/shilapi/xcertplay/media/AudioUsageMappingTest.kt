package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test

class AudioUsageMappingTest {
    @Test fun standardCategoriesStayDistinctUnlessExplicitlyChanged() {
        for(usage in listOf(1,2,6,12,16)) assertEquals(usage,mappedAudioUsage(usage,emptyMap()))
        assertEquals(12,mappedAudioUsage(1,mapOf(1 to 12)))
        assertEquals(2,mappedAudioUsage(2,mapOf(1 to 12)))
    }
    @Test fun invalidUsagesCannotSelectEmergencyOrUnknownCategories() {
        assertEquals(1,mappedAudioUsage(1,mapOf(1 to 1000)))
        assertEquals(4,mappedAudioUsage(4,mapOf(4 to 1)))
    }
}
