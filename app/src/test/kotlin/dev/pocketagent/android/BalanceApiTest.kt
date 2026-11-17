package dev.pocketagent.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BalanceApiTest {

    private val body = """
        {"is_available":true,"balance_infos":[{"currency":"CNY","total_balance":"24.74",
        "granted_balance":"0.00","topped_up_balance":"24.74"}]}
    """.trimIndent()

    @Test
    fun readsTheTotalAndCurrency() {
        val result = BalanceApi.parse(body)
        assertTrue(result is BalanceApi.Result.Ok)
        result as BalanceApi.Result.Ok
        assertEquals("24.74", result.total)
        assertEquals("CNY", result.currency)
    }

    @Test
    fun missingFieldsAreReportedNotGuessed() {
        assertTrue(BalanceApi.parse("""{"is_available":true}""") is BalanceApi.Result.Failed)
        assertTrue(BalanceApi.parse("""{"balance_infos":[{"currency":"CNY"}]}""") is BalanceApi.Result.Failed)
    }

    @Test
    fun aNumberIsParsedOrNull() {
        assertEquals(24.74, BalanceApi.amount("24.74")!!, 0.0001)
        assertNull(BalanceApi.amount("N/A"))
    }

    @Test
    fun theAmountIsShownInTheUsersMoney() {
        assertEquals("¥24.74", BalanceApi.show("24.74", "CNY"))
        assertEquals("$3.10", BalanceApi.show("3.10", "USD"))
        assertEquals("5 EUR", BalanceApi.show("5", "EUR"))
    }
}
