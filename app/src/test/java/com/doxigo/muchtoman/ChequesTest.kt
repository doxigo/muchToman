package com.doxigo.muchtoman

import androidx.compose.ui.text.AnnotatedString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** «چک‌ها»: the warning in words, the reminder's once-per-date, and the date as she types it. */
class ChequesTest {

    private val due = jalaliDay(1405, 9, 15)

    private fun cheque(id: String = "rent", rial: Long = 500_000_000, day: Long = due, name: String = "اجاره", at: Long = 0) =
        newCheque(id, name, rial, day, "MELLAT", now = at)!!

    /** ۳۰ میلیون تومان, stated by the bank. */
    private val mellat = BankAccount(bank = "MELLAT", balance = 30_000_000.0, anchored = true)

    @Test
    fun `a short account says how much it holds and how much is missing`() {
        val c = cheque()
        assertEquals(
            "تو حساب بانک ملت ۳۰ میلیون تومان هست؛ ۲۰ میلیون کمه.",
            chequeWarningFa(c, listOf(c), listOf(mellat), due - 1, daysBefore = 1),
        )
        // Further off than her window: nothing yet. Covered: nothing at all.
        assertNull(chequeWarningFa(c, listOf(c), listOf(mellat), due - 2, daysBefore = 1))
        assertNull(chequeWarningFa(c, listOf(c), listOf(mellat.copy(balance = 50_000_000.0)), due, daysBefore = 1))
    }

    @Test
    fun `an unknown balance is said as unknown, never as nothing in the account`() {
        val c = cheque()
        val unknown = "موجودی حساب بانک ملت رو نمی‌دونیم؛ مطمئن شو ۵۰ میلیون تومان توش هست."
        assertEquals(unknown, chequeWarningFa(c, listOf(c), emptyList(), due, daysBefore = 1))
        // A running sum of the messages read is not a balance.
        assertEquals(unknown, chequeWarningFa(c, listOf(c), listOf(mellat.copy(anchored = false)), due, daysBefore = 1))
    }

    @Test
    fun `on the day and past it the warning stands, reminders off or not`() {
        val c = cheque()
        assertEquals("امروز", chequeWhenFa(due, due))
        assertEquals("۳ روز مونده", chequeWhenFa(due, due - 3))
        assertEquals("۲ روز گذشته", chequeWhenFa(due, due + 2))
        val short = "تو حساب بانک ملت ۳۰ میلیون تومان هست؛ ۲۰ میلیون کمه."
        assertEquals(short, chequeWarningFa(c, listOf(c), listOf(mellat), due, daysBefore = -1))
        assertEquals(short, chequeWarningFa(c, listOf(c), listOf(mellat), due + 2, daysBefore = 1))
    }

    @Test
    fun `cheques on one account are covered together, the earlier one first`() {
        val first = cheque("a", rial = 200_000_000, at = 1)
        val second = cheque("b", rial = 200_000_000, at = 2)
        val open = openCheques(listOf(second, first))
        assertEquals(listOf(first, second), open)
        assertNull(chequeWarningFa(first, open, listOf(mellat), due, daysBefore = 1))
        assertEquals(
            "تو حساب بانک ملت ۳۰ میلیون تومان هست؛ با چک‌های زودتر همین حساب، ۱۰ میلیون کمه.",
            chequeWarningFa(second, open, listOf(mellat), due, daysBefore = 1),
        )
        // Home leads with the one that cannot be covered.
        assertEquals(second, pressingCheque(open, listOf(mellat), due, daysBefore = 1))
    }

    @Test
    fun `home's attention card is the cheque, warning and all`() {
        val c = cheque()
        val story = buildStory(emptyList(), 0L, due - 1, cheques = listOf(c), accounts = listOf(mellat))
        assertEquals(c, story.attentionCheque)
        assertEquals(
            "سررسید چک «اجاره» فرداست. تو حساب بانک ملت ۳۰ میلیون تومان هست؛ ۲۰ میلیون کمه.",
            story.attention?.text,
        )
        assertNull(buildStory(emptyList(), 0L, due - 3, cheques = listOf(c), accounts = listOf(mellat)).attentionCheque)
    }

    @Test
    fun `a passed cheque leaves the list, and other shapes were never on it`() {
        val passed = cheque("p").copy(endsOn = due)
        val plan = cheque("i").copy(kind = GoalKind.INSTALLMENT)
        val gone = cheque("d").copy(deleted = true)
        assertEquals(listOf(cheque()), openCheques(listOf(passed, plan, gone, cheque())))
        assertNull(newCheque("x", "", 0, due, "MELLAT", 0))
        assertNull(newCheque("x", "", 10, due, "", 0))
    }

    @Test
    fun `a reminder comes once per date, inside her days and waking hours`() {
        val c = cheque()
        fun at(day: Long, hour: Int) = tehranDayStart(day) + hour * 3_600_000L
        val (due1, marks) = chequeNews(listOf(c), 1, emptyMap(), at(due - 1, 10))
        assertEquals(listOf(c), due1)
        assertEquals(mapOf(c.id to due), marks)
        assertEquals(emptyList<Goal>(), chequeNews(listOf(c), 1, marks, at(due, 10)).first)
        assertEquals(emptyList<Goal>(), chequeNews(listOf(c), 1, emptyMap(), at(due - 1, 3)).first)
        assertEquals(emptyList<Goal>(), chequeNews(listOf(c), -1, emptyMap(), at(due, 10)).first)
        assertEquals("سررسید چک «اجاره» فرداست", chequeReminderTitle(c, due - 1))
        assertEquals("سررسید چک بانک ملت امروزه", chequeReminderTitle(cheque(name = " "), due))
        assertEquals("۵۰ میلیون تومان از بانک ملت • ۱۵ آذر ۱۴۰۵", chequeReminderBody(c, listOf(c), listOf(mellat.copy(balance = 9e7)), due, 1))
    }

    @Test
    fun `the date is typed as printed and refused when it is not one`() {
        assertEquals(due, parseChequeDate("۱۴۰۵۰۹۱۵"))
        assertEquals(due, parseChequeDate("1405/09/15"))
        assertEquals("14050915", chequeDateDigits(due))
        assertNull(parseChequeDate("14050731")) // مهر has thirty days
        assertNull(parseChequeDate("1405091"))
        assertNull(parseChequeDate("14051315"))
        val drawn = digitsMask('/', 4, 6).filter(AnnotatedString("14050915"))
        assertEquals("۱۴۰۵/۰۹/۱۵", drawn.text.text)
        assertEquals(listOf(0, 4, 6, 9, 10), listOf(0, 4, 5, 7, 8).map(drawn.offsetMapping::originalToTransformed))
        assertEquals(listOf(4, 4, 6, 6, 8), listOf(4, 5, 7, 8, 10).map(drawn.offsetMapping::transformedToOriginal))
        // Never a trailing separator.
        assertEquals("۱۴۰۵", digitsMask('/', 4, 6).filter(AnnotatedString("1405")).text.text)
    }
}
