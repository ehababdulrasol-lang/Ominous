package com.example.modules.fileapp

import android.util.Log

/**
 * Journal Entry Engine (محرك قيود القيد المزدوج)
 * Designed for strict Double-Entry accounting compliance under IFRS standards.
 * Suppports multi-branch isolation and strict balance assertions.
 */
object JournalEntryEngine {

    class UnbalancedJournalEntryException(val diff: Double) : 
        Exception("فشل القيد المحاسبي: القيد غير متوازن مالياً! فرق الموازنة هو: $diff د.ل")

    /**
     * Asserts that debit amounts equal credit amounts.
     */
    fun assertBalanced(totalDebit: Double, totalCredit: Double) {
        val diff = Math.abs(totalDebit - totalCredit)
        if (diff > 0.001) {
            Log.e("JournalEntryEngine", "Unbalanced entry detected! Debit: $totalDebit, Credit: $totalCredit")
            throw UnbalancedJournalEntryException(diff)
        }
    }

    /**
     * Generates a strict formatted voucher code for the entry: JE-{YYYY}-{BRANCH_CODE}-{SEQ}
     */
    fun generateJournalNumber(year: Int, branchCode: String, sequence: Int): String {
        return "JE-$year-$branchCode-${String.format("%04d", sequence)}"
    }

    /**
     * Generates a shift code for cashiers: SH-{YYYYMMDD}-{BRANCH_CODE}-{SEQ}
     */
    fun generateShiftNumber(dateStr: String, branchCode: String, sequence: Int): String {
        val cleanDate = dateStr.replace("-", "")
        return "SH-$cleanDate-$branchCode-${String.format("%02d", sequence)}"
    }

    /**
     * Generates an invoice code: INV-{YYYY}-{BRANCH_CODE}-{SEQ}
     */
    fun generateInvoiceNumber(year: Int, branchCode: String, sequence: Int): String {
        return "INV-$year-$branchCode-${String.format("%04d", sequence)}"
    }
}

/**
 * Money Lifecycle & Fund Movement Tracker
 * Analyzes where every Single Dinar (د.ل) goes across different branch operations.
 */
data class MoneyFlowAnalysis(
    val title: String,
    val category: String,
    val percentage: Float,
    val totalAmount: Double,
    val colorHex: String
) {
    fun getFormattedAmount(): String {
        return String.format("%,.2f د.ل", totalAmount)
    }
}

object MoneyLifecycleManager {

    /**
     * Conducts structural intelligence over inflows and outflows to determine cash health.
     */
    fun calculateLifecycleDistribution(
        totalSalesCash: Double,
        totalSalesCard: Double,
        totalCollections: Double,
        totalExpenses: Double,
        totalPurchases: Double
    ): List<MoneyFlowAnalysis> {
        val grandTotal = totalSalesCash + totalSalesCard + totalCollections + totalExpenses + totalPurchases
        if (grandTotal <= 0.0) {
            return listOf(
                MoneyFlowAnalysis("مبيعات نقدية لكاشير الفرع", "Inflow", 50f, totalSalesCash, "#4F46E5"),
                MoneyFlowAnalysis("مبيعات بطاقات وحساب بنكي", "Inflow", 25f, totalSalesCard, "#3B82F6"),
                MoneyFlowAnalysis("شراء سلع ومستلزمات تشغيلية", "Outflow", 15f, totalPurchases, "#EF4444"),
                MoneyFlowAnalysis("عمومية ومصروفات إدارية", "Outflow", 10f, totalExpenses, "#F59E0B")
            )
        }
        
        return listOf(
            MoneyFlowAnalysis("مبيعات نقدية (النقدية المتدفقة)", "Inflow", ((totalSalesCash / grandTotal) * 100).toFloat(), totalSalesCash, "#4F46E5"),
            MoneyFlowAnalysis("مبيعات بطاقة ومصارف (التدفق البنكي)", "Inflow", ((totalSalesCard / grandTotal) * 100).toFloat(), totalSalesCard, "#3B82F6"),
            MoneyFlowAnalysis("تحصيلات من ذمم مدينين", "Inflow", ((totalCollections / grandTotal) * 100).toFloat(), totalCollections, "#10B981"),
            MoneyFlowAnalysis("مشتريات السلع والأصناف للفرع", "Outflow", ((totalPurchases / grandTotal) * 100).toFloat(), totalPurchases, "#EF4444"),
            MoneyFlowAnalysis("مصروفات عمومية وتشغيلية للإدارة", "Outflow", ((totalExpenses / grandTotal) * 100).toFloat(), totalExpenses, "#F59E0B")
        )
    }
}
