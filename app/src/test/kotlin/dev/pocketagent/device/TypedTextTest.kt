package dev.pocketagent.device

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TypedTextTest {
    @Test
    fun acceptsTheSameText() {
        assertTrue(TypedText.matches("Blue Tokai Coffee", "Blue Tokai Coffee"))
    }

    @Test
    fun preservesCaseAndExtraSpaces() {
        assertFalse(TypedText.matches("Blue Tokai Coffee", "  blue   tokai coffee "))
    }

    @Test
    fun rejectsAFieldThatStillHoldsTheOldText() {
        assertFalse(TypedText.matches("Blue Tokai Coffee, Indiranagar", "test"))
    }

    @Test
    fun rejectsAnEmptyOrMissingField() {
        assertFalse(TypedText.matches("hello", ""))
        assertFalse(TypedText.matches("hello", null))
        assertFalse(TypedText.matches("hello", "   "))
    }

    @Test
    fun rejectsOldTextPlusNewText() {
        assertFalse(TypedText.matches("blue tokai", "testblue tokai"))
    }

    @Test
    fun rejectsATruncatedField() {
        assertFalse(TypedText.matches("blue tokai coffee", "blue tok"))
    }

    @Test
    fun preservesLineBreaksAndNormalisesOnlyLineEndings() {
        assertFalse(TypedText.matches("12345", "1,2345"))
        assertFalse(TypedText.matches("Line one\nline two", "line one line two"))
        assertTrue(TypedText.matches("Line one\nline two", "Line one\r\nline two"))
    }
}
