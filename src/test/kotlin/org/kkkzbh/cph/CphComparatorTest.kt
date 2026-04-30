package org.kkkzbh.cph

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CphComparatorTest {
    @Test
    fun finalLineEndingDoesNotCauseWrongAnswer() {
        val (accepted, _) = CphComparator.compare(
            actual = "1\n",
            expected = "1",
            ignoreTrailingWhitespace = false,
        )

        assertTrue(accepted)
    }

    @Test
    fun trailingSpacesRemainStrictUnlessIgnored() {
        val (strictAccepted, _) = CphComparator.compare(
            actual = "1   \n\n",
            expected = "1",
            ignoreTrailingWhitespace = false,
        )
        val (relaxedAccepted, _) = CphComparator.compare(
            actual = "1   \n\n",
            expected = "1",
            ignoreTrailingWhitespace = true,
        )

        assertFalse(strictAccepted)
        assertTrue(relaxedAccepted)
    }

    @Test
    fun differingActualLinesReportsChangedLines() {
        assertEquals(
            setOf(0),
            CphComparator.differingActualLines(
                actual = "1",
                expected = "6",
                ignoreTrailingWhitespace = true,
            ),
        )
        assertEquals(
            setOf(1),
            CphComparator.differingActualLines(
                actual = "1\n2",
                expected = "1\n3",
                ignoreTrailingWhitespace = true,
            ),
        )
        assertEquals(
            setOf(1, 3),
            CphComparator.differingActualLines(
                actual = "4\n5\n6\n7",
                expected = "4\n4\n6\n8",
                ignoreTrailingWhitespace = true,
            ),
        )
    }

    @Test
    fun differingActualLinesFollowsWhitespaceSetting() {
        assertEquals(
            emptySet<Int>(),
            CphComparator.differingActualLines(
                actual = "1   \n\n",
                expected = "1",
                ignoreTrailingWhitespace = true,
            ),
        )
        assertEquals(
            setOf(0),
            CphComparator.differingActualLines(
                actual = "1   \n\n",
                expected = "1",
                ignoreTrailingWhitespace = false,
            ),
        )
    }

    @Test
    fun differingActualLinesReportsExtraActualLines() {
        assertEquals(
            setOf(1),
            CphComparator.differingActualLines(
                actual = "1\n2",
                expected = "1",
                ignoreTrailingWhitespace = true,
            ),
        )
    }
}
