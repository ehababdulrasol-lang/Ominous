package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.theme.*
import com.example.modules.fileapp.JournalEntryEngine
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.*

// ==========================================
// MODELS & CORE DATA STRUCTURES
// ==========================================

enum class Branch(val id: Int, val code: String, val nameAr: String, val cashier: String, val managerAr: String, val inventoryCategory: String) {
    MAIN(1, "MAIN", "الفرع الرئيسي", "أحمد علي", "أ. عبد الرحمن التاجوري", "الأجهزة والمعدات التشغيلية"),
    TRIPOLI(2, "TRIP", "فرع طرابلس", "طارق محمد", "أ. محمد الكاديكي", "المستلزمات والمخازن الفرعية"),
    BENGHAZI(3, "BENG", "فرع بنغازي", "سالم عثمان", "أ. فتحي العبيدي", "السلع واللوجستيات والمخازن الرئيسية")
}

data class SystemAccount(
    val code: String,
    val nameAr: String,
    val type: AccountType,
    val normalBalance: NormalBalance
)

enum class AccountType { ASSET, LIABILITY, EQUITY, REVENUE, EXPENSE }
enum class NormalBalance { DEBIT, CREDIT }

data class JournalEntryLine(
    val accountCode: String,
    val accountName: String,
    val debit: Double = 0.0,
    val credit: Double = 0.0,
    val description: String = ""
)

data class JournalEntry(
    val id: String,
    val branch: Branch,
    val entryNumber: String,
    val date: String,
    val referenceType: String, // sale|purchase|transfer|adjustment|closure|opening
    val descriptionAr: String,
    val status: String, // Draft | Posted | Voided
    val totalDebit: Double,
    val totalCredit: Double,
    val lines: List<JournalEntryLine>,
    val createdBy: String,
    val createdAt: String
)

data class InvoiceItem(
    val descriptionAr: String,
    val quantity: Int,
    val unitPrice: Double,
    val discountPercent: Double = 0.0,
    val taxPercent: Double = 5.0
) {
    val subtotal: Double get() = quantity * unitPrice
    val discountAmount: Double get() = subtotal * (discountPercent / 100)
    val taxAmount: Double get() = (subtotal - discountAmount) * (taxPercent / 100)
    val totalAmount: Double get() = subtotal - discountAmount + taxAmount
}

data class Invoice(
    val id: String,
    val invoiceNumber: String,
    val branch: Branch,
    val type: String, // Sale | Purchase | Sale Return | Purchase Return
    val partyName: String,
    val paymentMethod: String, // Cash | Card | Bank | Credit
    val status: String, // Paid | Partial | Unpaid | Cancelled
    val date: String,
    val subtotal: Double,
    val discountAmount: Double,
    val taxAmount: Double,
    val totalAmount: Double,
    val paidAmount: Double,
    val remainingAmount: Double,
    val lines: List<InvoiceItem>,
    val notes: String
)

data class Party(
    val id: String,
    val nameAr: String,
    val code: String,
    val type: String, // Customer | Supplier
    val phone: String,
    val balance: Double // Positive means debit (customer owes), negative means credit (we owe supplier)
)

data class Shift(
    val branch: Branch,
    val shiftNumber: String,
    val cashierName: String,
    val openingBalance: Double,
    val expectedClosing: Double,
    val actualClosing: Double?,
    val difference: Double,
    val status: String, // Open | Pending Review | Closed
    val openedAt: String,
    val closedAt: String?
)

data class DailyClosure(
    val id: String,
    val branch: Branch,
    val closureDate: String,
    val status: String, // Completed | Pending
    val totalRevenue: Double,
    val totalExpenses: Double,
    val netIncome: Double,
    val closingCash: Double,
    val closingBank: Double,
    val notes: String,
    val processedAt: String
)

data class MoneyFlow(
    val id: String,
    val branch: Branch,
    val direction: String, // Inflow | Outflow | Internal
    val category: String, // Sales Cash | Sales Card | Bank Transfer | Expense | Inter-branch
    val amount: Double,
    val description: String,
    val date: String
)

// ==========================================
// VIEWMODEL FOR FINANCIAL STATE
// ==========================================

class FinancialViewModel : ViewModel() {
    // Global Settings Configuration
    var companyName by mutableStateOf("شركة اليمامة للحلول المالية والمقاولات")
    var baseCurrency by mutableStateOf("د.ل")
    var defaultTaxRate by mutableStateOf(5.0)
    var cashLimitWarning by mutableStateOf(50000.0)
    var isAutoPostingEnabled by mutableStateOf(true)
    var currentFiscalYearName by mutableStateOf("السنة المالية 2026")
    var isDarkModeEnabled by mutableStateOf(false)

    // Current state indicators
    var currentBranch by mutableStateOf(Branch.MAIN)
    var currentTab by mutableStateOf("home")
    var toastMessage by mutableStateOf<String?>(null)

    // Form Dialog open triggers
    var isNewInvoiceOpen by mutableStateOf(false)
    var isNewJournalOpen by mutableStateOf(false)
    var isTransferOpen by mutableStateOf(false)
    var isClosureOpen by mutableStateOf(false)
    var isNewPartyOpen by mutableStateOf(false)
    var activeTransactionForSidebar by mutableStateOf<JournalEntry?>(null)

    // Seed/Dynamic data lists
    val systemAccounts = listOf(
        SystemAccount("1111", "خزينة الفرع", AccountType.ASSET, NormalBalance.DEBIT),
        SystemAccount("1121", "حساب بنكي الفرع", AccountType.ASSET, NormalBalance.DEBIT),
        SystemAccount("1130", "ذمم مدينة (العملاء)", AccountType.ASSET, NormalBalance.DEBIT),
        SystemAccount("2100", "ذمم دائنة (الموردين)", AccountType.LIABILITY, NormalBalance.CREDIT),
        SystemAccount("3100", "رأس المال", AccountType.EQUITY, NormalBalance.CREDIT),
        SystemAccount("3200", "الأرباح المحتجزة / المرحلة", AccountType.EQUITY, NormalBalance.CREDIT),
        SystemAccount("4100", "إيرادات المبيعات", AccountType.REVENUE, NormalBalance.CREDIT),
        SystemAccount("5200", "مصروفات تشغيلية وعمومية", AccountType.EXPENSE, NormalBalance.DEBIT)
    )

    var parties = mutableStateListOf(
        Party("1", "العميل: محمد الورفلي", "CUST-001", "Customer", "0912345678", 12500.0),
        Party("2", "المورد: شركة المدار", "SUPP-001", "Supplier", "0921112233", -3000.0),
        Party("3", "الزبون: مجموعة النجم", "CUST-002", "Customer", "0919998877", 0.0),
        Party("4", "المورد: شركة الليث", "SUPP-002", "Supplier", "0925556677", 0.0)
    )

    // Simulated ledger balances by branch to accurately update balances
    val branchBalances = mutableStateMapOf<Branch, Pair<Double, Double>>(
        Branch.MAIN to Pair(42300.50, 203589.50), // Cash, Bank
        Branch.TRIPOLI to Pair(18200.00, 89100.00),
        Branch.BENGHAZI to Pair(25120.00, 114500.0)
    )

    // Seed transaction list matching the editorial spec
    var journalEntries = mutableStateListOf<JournalEntry>(
        JournalEntry(
            id = "JE-1",
            branch = Branch.MAIN,
            entryNumber = "JE-2025-MAIN-001",
            date = "2026-06-11",
            referenceType = "opening",
            descriptionAr = "قيد الأرصدة الافتتاحية المعتمدة",
            status = "Posted",
            totalDebit = 245890.00,
            totalCredit = 245890.00,
            lines = listOf(
                JournalEntryLine("1111", "خزينة الفرع", debit = 42300.50),
                JournalEntryLine("1121", "حساب بنكي الفرع", debit = 203589.50),
                JournalEntryLine("3100", "رأس المال", credit = 245890.00)
            ),
            createdBy = "أدمن النظام",
            createdAt = "10:00 ص"
        ),
        JournalEntry(
            id = "JE-2",
            branch = Branch.MAIN,
            entryNumber = "JE-2025-MAIN-101",
            date = "2026-06-11",
            referenceType = "sale",
            descriptionAr = "فاتورة مبيعات نقدية رقم INV-024",
            status = "Posted",
            totalDebit = 1250.00,
            totalCredit = 1250.00,
            lines = listOf(
                JournalEntryLine("1111", "خزينة الفرع", debit = 1250.00),
                JournalEntryLine("4100", "إيرادات المبيعات", credit = 1250.00)
            ),
            createdBy = "أحمد علي",
            createdAt = "10:45 ص"
        ),
        JournalEntry(
            id = "JE-3",
            branch = Branch.MAIN,
            entryNumber = "JE-2025-MAIN-102",
            date = "2026-06-10",
            referenceType = "purchase",
            descriptionAr = "دفعة مورد ومشتريات: شركة المدار",
            status = "Posted",
            totalDebit = 3000.00,
            totalCredit = 3000.00,
            lines = listOf(
                JournalEntryLine("2100", "ذمم دائنة (الموردين)", debit = 3000.00),
                JournalEntryLine("1111", "خزينة الفرع", credit = 3000.00)
            ),
            createdBy = "أحمد علي",
            createdAt = "04:30 م"
        )
    )

    var invoices = mutableStateListOf<Invoice>(
        Invoice(
            id = "INV-24",
            invoiceNumber = "INV-2025-MAIN-024",
            branch = Branch.MAIN,
            type = "Sale",
            partyName = "العميل: محمد الورفلي",
            paymentMethod = "Cash",
            status = "Paid",
            date = "2026-06-11",
            subtotal = 1190.48,
            discountAmount = 0.0,
            taxAmount = 59.52,
            totalAmount = 1250.0,
            paidAmount = 1250.0,
            remainingAmount = 0.0,
            lines = listOf(InvoiceItem("خدمات استشارية مالية وتدريب", 1, 1190.48, taxPercent = 5.0)),
            notes = "دفعت نقداً بالفرع"
        ),
        Invoice(
            id = "INV-25",
            invoiceNumber = "INV-2025-MAIN-025",
            branch = Branch.MAIN,
            type = "Purchase",
            partyName = "المورد: شركة المدار",
            paymentMethod = "Cash",
            status = "Paid",
            date = "2026-06-10",
            subtotal = 3000.0,
            discountAmount = 0.0,
            taxAmount = 0.0,
            totalAmount = 3000.0,
            paidAmount = 3000.0,
            remainingAmount = 0.0,
            lines = listOf(InvoiceItem("مصروفات اشتراكات خطوط واتصالات", 12, 250.0, taxPercent = 0.0)),
            notes = "مشتريات مسددة بالكامل"
        )
    )

    // Money flows trace
    var moneyFlows = mutableStateListOf<MoneyFlow>(
        MoneyFlow("MF-1", Branch.MAIN, "Inflow", "Sales Cash", 1250.0, "فاتورة مبيعات INV-024", "2026-06-11"),
        MoneyFlow("MF-2", Branch.MAIN, "Outflow", "Expense", 3000.0, "دفعة مورد شركة المدار", "2026-06-10")
    )

    // Shift registers simulation
    var branchShifts = mutableStateMapOf<Branch, Shift>(
        Branch.MAIN to Shift(Branch.MAIN, "SH-20260611-MAIN-01", "أحمد علي", 40000.0, 42300.5, null, 0.0, "Open", "08:00 ص", null),
        Branch.TRIPOLI to Shift(Branch.TRIPOLI, "SH-20260611-TRIP-01", "طارق محمد", 15000.0, 18200.0, null, 0.0, "Open", "08:15 ص", null),
        Branch.BENGHAZI to Shift(Branch.BENGHAZI, "SH-20260611-BENG-01", "سالم عثمان", 20000.0, 25120.0, null, 0.0, "Open", "08:30 ص", null)
    )

    // Daily Closures history
    var dailyClosures = mutableStateListOf<DailyClosure>(
        DailyClosure("DC-01", Branch.MAIN, "2026-06-10", "Completed", 14500.0, 3200.0, 11300.0, 41050.50, 201000.0, "تم ترحيل قيد الأرباح وإغلاق الورديات بدون فروقات مالية", "2026-06-10 08:00 م")
    )

    // Helper functions for updating state safely with Double-Entry math
    fun showToast(msg: String) {
        toastMessage = msg
    }

    // ADD NEW CUSTOMER OR SUPPLIER DYNAMICALLY
    fun addParty(name: String, type: String, phone: String, initialBalance: Double): Boolean {
        if (name.isBlank()) {
            showToast("الرجاء إدخال اسم العميل أو المورد!")
            return false
        }
        val prefix = if (type == "Customer") "CUST" else "SUPP"
        val count = parties.filter { it.type == type }.size + 1
        val newCode = "$prefix-${String.format("%03d", count)}"
        val prefixedName = if (type == "Customer") "العميل: $name" else "المورد: $name"

        val newParty = Party(
            id = (parties.size + 1).toString(),
            nameAr = prefixedName,
            code = newCode,
            type = type,
            phone = phone,
            balance = initialBalance
        )
        parties.add(newParty)
        showToast("✅ تم إضافة ${if (type == "Customer") "العميل" else "المورد"} الجديد بنجاح!")
        return true
    }

    // CREATE MANUALLY BALANCED JOURNAL ENTRY
    fun addManualJournalEntry(
        description: String,
        debitAccount: SystemAccount,
        debitAmount: Double,
        creditAccount: SystemAccount,
        creditAmount: Double
    ): Boolean {
        if (debitAmount <= 0.0 || creditAmount <= 0.0) {
            showToast("تنبيه: يجب أن تكون القيمة أكبر من صفر!")
            return false
        }
        try {
            JournalEntryEngine.assertBalanced(debitAmount, creditAmount)
        } catch (e: JournalEntryEngine.UnbalancedJournalEntryException) {
            showToast(e.message ?: "⚠️ القيد غير متوازن مالياً!")
            return false
        }

        val seq = journalEntries.size + 1
        val entryNum = JournalEntryEngine.generateJournalNumber(2026, currentBranch.code, seq)
        val timeNow = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date())

        val lines = listOf(
            JournalEntryLine(debitAccount.code, debitAccount.nameAr, debit = debitAmount),
            JournalEntryLine(creditAccount.code, creditAccount.nameAr, credit = creditAmount)
        )

        val transaction = JournalEntry(
            id = "JE-$seq",
            branch = currentBranch,
            entryNumber = entryNum,
            date = "2026-06-11",
            referenceType = "adjustment",
            descriptionAr = description,
            status = "Posted",
            totalDebit = debitAmount,
            totalCredit = creditAmount,
            lines = lines,
            createdBy = currentBranch.cashier,
            createdAt = timeNow
        )

        // Inject into list
        journalEntries.add(0, transaction)

        // Dynamically update cash or bank accounts balances if affected
        var currentCash = branchBalances[currentBranch]?.first ?: 0.0
        var currentBank = branchBalances[currentBranch]?.second ?: 0.0

        if (debitAccount.code == "1111") currentCash += debitAmount
        if (creditAccount.code == "1111") currentCash -= creditAmount
        if (debitAccount.code == "1121") currentBank += debitAmount
        if (creditAccount.code == "1121") currentBank -= creditAmount

        branchBalances[currentBranch] = Pair(currentCash, currentBank)

        // Money flow tracking
        val category = if (debitAccount.code == "1111" || creditAccount.code == "1111") "Cash Ledger" else "Bank Ledger"
        moneyFlows.add(
            0, MoneyFlow(
                id = "MF-${System.currentTimeMillis()}",
                branch = currentBranch,
                direction = "Internal",
                category = category,
                amount = debitAmount,
                description = description,
                date = "2026-06-11"
            )
        )

        showToast("✅ تم ترحيل القيد المحاسبي بنجاح وتحديث الحسابات بنفاط القيد المزدوج!")
        return true
    }

    // CREATE NEW SALES OR PURCHASES INVOICE
    fun addInvoice(
        type: String, // Sale | Purchase
        partyName: String,
        paymentMethod: String, // Cash | Card | Bank | Credit
        productName: String,
        quantity: Int,
        unitPrice: Double,
        discountPercent: Double,
        taxPercent: Double,
        notes: String
    ): Boolean {
        if (quantity <= 0 || unitPrice <= 0.0) {
            showToast("الرجاء توفير كمية وسعر صالحين")
            return false
        }

        val item = InvoiceItem(productName, quantity, unitPrice, discountPercent, taxPercent)
        val calculatedTotal = item.totalAmount
        val discountAmount = item.discountAmount
        val taxAmount = item.taxAmount
        val subtotal = item.subtotal

        val paidAmount = if (paymentMethod == "Credit") 0.0 else calculatedTotal
        val remainingAmount = if (paymentMethod == "Credit") calculatedTotal else 0.0

        val nextSeq = invoices.size + 1
        val invNumber = JournalEntryEngine.generateInvoiceNumber(2026, currentBranch.code, nextSeq)

        val invoiceObj = Invoice(
            id = "INV-$nextSeq",
            invoiceNumber = invNumber,
            branch = currentBranch,
            type = type,
            partyName = partyName,
            paymentMethod = paymentMethod,
            status = if (paymentMethod == "Credit") "Unpaid" else "Paid",
            date = "2026-06-11",
            subtotal = subtotal,
            discountAmount = discountAmount,
            taxAmount = taxAmount,
            totalAmount = calculatedTotal,
            paidAmount = paidAmount,
            remainingAmount = remainingAmount,
            lines = listOf(item),
            notes = notes
        )

        invoices.add(0, invoiceObj)

        // Journal logic automatically derived
        val timeNow = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date())
        val debitAcctCode = if (paymentMethod == "Credit") "1130" else if (paymentMethod == "Bank" || paymentMethod == "Card") "1121" else "1111"
        val debitAcctName = if (paymentMethod == "Credit") "ذمم مدينة (العملاء)" else if (paymentMethod == "Bank" || paymentMethod == "Card") "حساب بنكي الفرع" else "خزينة الفرع"

        val creditAcctCode = if (type == "Sale") "4100" else "2100"
        val creditAcctName = if (type == "Sale") "إيرادات المبيعات" else "ذمم دائنة (الموردين)"

        val lines = listOf(
            JournalEntryLine(debitAcctCode, debitAcctName, debit = calculatedTotal),
            JournalEntryLine(creditAcctCode, creditAcctName, credit = calculatedTotal)
        )

        val entryNum = "JE-2025-${currentBranch.code}-${String.format("%03d", journalEntries.size + 1)}"
        val associatedJournal = JournalEntry(
            id = "JE-${journalEntries.size + 1}",
            branch = currentBranch,
            entryNumber = entryNum,
            date = "2026-06-11",
            referenceType = if (type == "Sale") "sale" else "purchase",
            descriptionAr = "فاتورة $type رقم ${invoiceObj.invoiceNumber}",
            status = "Posted",
            totalDebit = calculatedTotal,
            totalCredit = calculatedTotal,
            lines = lines,
            createdBy = currentBranch.cashier,
            createdAt = timeNow
        )

        journalEntries.add(0, associatedJournal)

        // Adjust Branch Assets State
        var currentCash = branchBalances[currentBranch]?.first ?: 0.0
        var currentBank = branchBalances[currentBranch]?.second ?: 0.0

        if (paymentMethod == "Cash") {
            if (type == "Sale") currentCash += calculatedTotal else currentCash -= calculatedTotal
        } else if (paymentMethod == "Bank" || paymentMethod == "Card") {
            if (type == "Sale") currentBank += calculatedTotal else currentBank -= calculatedTotal
        }

        branchBalances[currentBranch] = Pair(currentCash, currentBank)

        // Money flows registry update
        val direction = if (type == "Sale") "Inflow" else "Outflow"
        val category = if (paymentMethod == "Cash") "Sales Cash" else if (paymentMethod == "Credit") "Sales Credit" else "Bank Transfer"
        moneyFlows.add(
            0, MoneyFlow(
                id = "MF-${System.currentTimeMillis()}",
                branch = currentBranch,
                direction = direction,
                category = category,
                amount = calculatedTotal,
                description = "فاتورة $type $invNumber",
                date = "2026-06-11"
            )
        )

        // If credit, adjust customer credit balance
        if (paymentMethod == "Credit") {
            val matchedPartyIndex = parties.indexOfFirst { it.nameAr.contains(partyName) || partyName.contains(it.nameAr) }
            if (matchedPartyIndex != -1) {
                val matchedParty = parties[matchedPartyIndex]
                val updatedBalance = if (type == "Sale") matchedParty.balance + calculatedTotal else matchedParty.balance - calculatedTotal
                parties[matchedPartyIndex] = matchedParty.copy(balance = updatedBalance)
            }
        }

        showToast("✅ تم حفظ الفاتورة بنجاح ومزامنة القيود المزدوجة ومخطط السيولة!")
        return true
    }

    // MULTI-BRANCH FUNDS TRANSFER
    fun addInterbranchTransfer(
        targetBranch: Branch,
        transferType: String, // Box Cash | Bank account
        amount: Double,
        referenceNum: String,
        notes: String
    ): Boolean {
        if (amount <= 0.0) {
            showToast("القيمة يجب أن تكون أكبر من الصفر")
            return false
        }
        if (targetBranch == currentBranch) {
            showToast("تنبيه: لا يمكن التحويل لنفس الفرع!")
            return false
        }

        var sourceCash = branchBalances[currentBranch]?.first ?: 0.0
        var sourceBank = branchBalances[currentBranch]?.second ?: 0.0

        if (transferType == "Box Cash" && sourceCash < amount) {
            showToast("❌ رصيد خزينة الفرع غير كافٍ لإتمام التحويل!")
            return false
        }
        if (transferType == "Bank account" && sourceBank < amount) {
            showToast("❌ رصيد بنك الفرع غير كافٍ لإتمام التحويل!")
            return false
        }

        val timeNow = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date())

        // Debit source side, credit target side
        if (transferType == "Box Cash") {
            sourceCash -= amount
            branchBalances[currentBranch] = Pair(sourceCash, sourceBank)

            var destCash = branchBalances[targetBranch]?.first ?: 0.0
            var destBank = branchBalances[targetBranch]?.second ?: 0.0
            destCash += amount
            branchBalances[targetBranch] = Pair(destCash, destBank)
        } else {
            sourceBank -= amount
            branchBalances[currentBranch] = Pair(sourceCash, sourceBank)

            var destCash = branchBalances[targetBranch]?.first ?: 0.0
            var destBank = branchBalances[targetBranch]?.second ?: 0.0
            destBank += amount
            branchBalances[targetBranch] = Pair(destCash, destBank)
        }

        // Post balanced accounting entries for BOTH branches
        // Source branch journal entry
        val sourceEntryNum = "JE-2025-${currentBranch.code}-TR-${System.currentTimeMillis() % 1000}"
        val sourceLines = listOf(
            JournalEntryLine("1111", "حساب التحويلات البينية", debit = amount),
            JournalEntryLine(if (transferType == "Box Cash") "1111" else "1121", if (transferType == "Box Cash") "خزينة الفرع" else "حساب بنكي الفرع", credit = amount)
        )
        journalEntries.add(
            0, JournalEntry(
                id = "JE-TR-SRC-${System.currentTimeMillis()}",
                branch = currentBranch,
                entryNumber = sourceEntryNum,
                date = "2026-06-11",
                referenceType = "transfer",
                descriptionAr = "تحويل صندوق صادر إلى ${targetBranch.nameAr} - مرجع $referenceNum",
                status = "Posted",
                totalDebit = amount,
                totalCredit = amount,
                lines = sourceLines,
                createdBy = currentBranch.cashier,
                createdAt = timeNow
            )
        )

        // Target branch journal entry
        val targetEntryNum = "JE-2025-${targetBranch.code}-TR-${System.currentTimeMillis() % 1000}"
        val targetLines = listOf(
            JournalEntryLine(if (transferType == "Box Cash") "1111" else "1121", if (transferType == "Box Cash") "خزينة الفرع" else "حساب بنكي الفرع", debit = amount),
            JournalEntryLine("1111", "حساب التحويلات البينية", credit = amount)
        )
        journalEntries.add(
            0, JournalEntry(
                id = "JE-TR-DST-${System.currentTimeMillis()}",
                branch = targetBranch,
                entryNumber = targetEntryNum,
                date = "2026-06-11",
                referenceType = "transfer",
                descriptionAr = "تحويل صندوق وارد من منشأة ${currentBranch.nameAr} - مرجع $referenceNum",
                status = "Posted",
                totalDebit = amount,
                totalCredit = amount,
                lines = targetLines,
                createdBy = targetBranch.cashier,
                createdAt = timeNow
            )
        )

        // Money flow records
        moneyFlows.add(
            0, MoneyFlow(
                id = "MF-TR-1-${System.currentTimeMillis()}",
                branch = currentBranch,
                direction = "Outflow",
                category = "Inter-branch",
                amount = amount,
                description = "تحويل صادر إلى ${targetBranch.nameAr}",
                date = "2026-06-11"
            )
        )
        moneyFlows.add(
            0, MoneyFlow(
                id = "MF-TR-2-${System.currentTimeMillis()}",
                branch = targetBranch,
                direction = "Inflow",
                category = "Inter-branch",
                amount = amount,
                description = "تحويل وارد من ${currentBranch.nameAr}",
                date = "2026-06-11"
            )
        )

        showToast("✅ تم إتمام الحوالة البينية لـ ${targetBranch.nameAr} وتوليد القيود المزدوجة المتوازنة بالفرعين!")
        return true
    }

    // DAILY CLOSURE AND EXPECTED BALANCES
    fun runDailyClosure(actualClosing: Double, notes: String): Boolean {
        val branchCash = branchBalances[currentBranch]?.first ?: 0.0
        val difference = actualClosing - branchCash

        val seq = dailyClosures.size + 1
        val timeNowStr = SimpleDateFormat("yyyy-MM-dd hh:mm a", Locale.getDefault()).format(Date())

        val revenueToday = invoices.filter { it.branch == currentBranch && it.type == "Sale" }.sumOf { it.totalAmount }
        val expensesToday = invoices.filter { it.branch == currentBranch && it.type == "Purchase" }.sumOf { it.totalAmount }

        val closureObj = DailyClosure(
            id = "DC-$seq",
            branch = currentBranch,
            closureDate = "2026-06-11",
            status = "Completed",
            totalRevenue = revenueToday,
            totalExpenses = expensesToday,
            netIncome = revenueToday - expensesToday,
            closingCash = actualClosing,
            closingBank = branchBalances[currentBranch]?.second ?: 0.0,
            notes = "إقفال اليوم المالي: $notes | فروقات تسوية: $difference د.ل",
            processedAt = timeNowStr
        )

        dailyClosures.add(0, closureObj)

        // Shift is updated to closed
        val activeShift = branchShifts[currentBranch]
        if (activeShift != null) {
            branchShifts[currentBranch] = activeShift.copy(
                status = "Closed",
                actualClosing = actualClosing,
                difference = difference,
                closedAt = timeNowStr
            )
        }

        // Generate automatic closure journal entries
        if (revenueToday > 0.0 || expensesToday > 0.0) {
            val closeEntryNum = "JE-2025-${currentBranch.code}-CL-${seq}"
            val closeJournalLines = listOf(
                JournalEntryLine("4100", "إيرادات المبيعات (إغلاق)", debit = revenueToday),
                JournalEntryLine("5200", "مصروفات تشغيلية وعمومية (إغلاق)", credit = expensesToday),
                JournalEntryLine("3200", "الأرباح المحتجزة والمرحلة", credit = revenueToday - expensesToday)
            )

            journalEntries.add(
                0, JournalEntry(
                    id = "JE-CL-$seq",
                    branch = currentBranch,
                    entryNumber = closeEntryNum,
                    date = "2026-06-11",
                    referenceType = "closure",
                    descriptionAr = "قيد الإغلاق التلقائي لليوم - ترحيل صافي الدخل",
                    status = "Posted",
                    totalDebit = revenueToday,
                    totalCredit = expensesToday + (revenueToday - expensesToday),
                    lines = closeJournalLines,
                    createdBy = "النظام المالي الموحد",
                    createdAt = timeNowStr
                )
            )
        }

        showToast("⚖️ تم تنفيذ الإغلاق المالي لـ ${currentBranch.nameAr} بنجاح وترحيل فروقات بقيمة $difference د.ل!")
        return true
    }
}

// ==========================================
// ACTIVITY BOOTSTRAP
// ==========================================

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                MainLayout()
            }
        }
    }
}

// ==========================================
// MAIN COMPOSE NAVIGATION LAYOUT
// ==========================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainLayout(viewModel: FinancialViewModel = viewModel()) {
    val snackbarHostState = remember { SnackbarHostState() }

    // RTL direction configuration for Arabic elements
    CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Rtl) {
        // Handle trigger events
        LaunchedEffect(viewModel.toastMessage) {
            viewModel.toastMessage?.let { msg ->
                snackbarHostState.showSnackbar(msg)
                viewModel.toastMessage = null
            }
        }

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = { TopFinancialAppBar(viewModel) },
            bottomBar = { BottomM3NavBar(viewModel) }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .padding(innerPadding)
            ) {
                // Content Switcher
                AnimatedContent(
                    targetState = viewModel.currentTab,
                    modifier = Modifier.fillMaxSize(),
                    transitionSpec = {
                        fadeIn(animationSpec = tween(250)) togetherWith fadeOut(animationSpec = tween(200))
                    },
                    label = "TabTransition"
                ) { targetTab ->
                    when (targetTab) {
                        "home" -> HomeScreen(viewModel)
                        "entries" -> JointEntriesScreen(viewModel)
                        "invoices" -> InvoicesScreen(viewModel)
                        "reports" -> AdvancedReportsScreen(viewModel)
                        "settings" -> SettingsScreen(viewModel)
                    }
                }

                // Global overlay Dialog forms
                if (viewModel.isNewInvoiceOpen) {
                    NewInvoiceDialog(viewModel) { viewModel.isNewInvoiceOpen = false }
                }
                if (viewModel.isNewJournalOpen) {
                    NewJournalEntryDialog(viewModel) { viewModel.isNewJournalOpen = false }
                }
                if (viewModel.isTransferOpen) {
                    InterBranchTransferDialog(viewModel) { viewModel.isTransferOpen = false }
                }
                if (viewModel.isClosureOpen) {
                    DailyClosureDialog(viewModel) { viewModel.isClosureOpen = false }
                }
                if (viewModel.isNewPartyOpen) {
                    NewPartyDialog(viewModel) { viewModel.isNewPartyOpen = false }
                }

                // Right Sidebar for Transaction Details & Audit Trail
                AnimatedVisibility(
                    visible = viewModel.activeTransactionForSidebar != null,
                    enter = slideInHorizontally(initialOffsetX = { it }),
                    exit = slideOutHorizontally(targetOffsetX = { it }),
                    modifier = Modifier.align(Alignment.CenterEnd)
                ) {
                    viewModel.activeTransactionForSidebar?.let { entry ->
                        AuditTrailSidebar(
                            entry = entry,
                            onClose = { viewModel.activeTransactionForSidebar = null },
                            viewModel = viewModel
                        )
                    }
                }
            }
        }
    }
}

// ==========================================
// TOP APP BAR COMPONENT WITH BRANCH LOGO (RE-DESIGNED)
// ==========================================

@Composable
fun TopFinancialAppBar(viewModel: FinancialViewModel) {
    var expandedBranchList by remember { mutableStateOf(false) }
    
    // Ambient pulsation animation for the "connected" state indicator
    val infiniteTransition = rememberInfiniteTransition(label = "PulsingGlow")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "AlphaAnimation"
    )

    Surface(
        color = Color.White,
        shadowElevation = 8.dp,
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Branch Logo and Text Label Group
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .clickable { expandedBranchList = true }
                    .testTag("branch_selector_trigger")
            ) {
                // Logo initial "ن" with vibrant visual gradient
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            Brush.linearGradient(
                                colors = listOf(EditorialPrimary, EditorialSecondary)
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "ن",
                        color = Color.White,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Black
                    )
                }

                // Text info
                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = viewModel.currentBranch.nameAr,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Black,
                            color = EditorialOnBackground
                        )
                        Icon(
                            imageVector = Icons.Default.ArrowDropDown,
                            contentDescription = "قائمة الفروع",
                            tint = EditorialPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // Pulsing Green Indicator Dot for Real-time Connected Sync
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(EditorialIncomeText.copy(alpha = alpha))
                        )
                        Text(
                            text = "النظام المالي الموحد • نوات متزامنة",
                            fontSize = 11.sp,
                            color = EditorialOnSurfaceVariant,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                // Dropdown menu to switch branches (Styled luxuriously)
                DropdownMenu(
                    expanded = expandedBranchList,
                    onDismissRequest = { expandedBranchList = false },
                    modifier = Modifier
                        .background(Color.White)
                        .border(BorderStroke(1.dp, Color(0xFFE2E8F0)), RoundedCornerShape(12.dp))
                ) {
                    Branch.values().forEach { br ->
                        DropdownMenuItem(
                            text = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    // Highlight marker for the active branch selection
                                    if (br == viewModel.currentBranch) {
                                        Box(
                                            modifier = Modifier
                                                .size(6.dp)
                                                .clip(CircleShape)
                                                .background(EditorialPrimary)
                                        )
                                    }
                                    Text(
                                        text = br.nameAr,
                                        fontWeight = if (br == viewModel.currentBranch) FontWeight.ExtraBold else FontWeight.Normal,
                                        color = if (br == viewModel.currentBranch) EditorialPrimary else EditorialOnBackground,
                                        fontSize = 13.sp
                                    )
                                }
                            },
                            onClick = {
                                viewModel.currentBranch = br
                                expandedBranchList = false
                                viewModel.showToast("تم تبديل النطاق والبيانات المالية إلى: ${br.nameAr}")
                            },
                            modifier = Modifier.testTag("branch_option_${br.code}")
                        )
                    }
                }
            }

            // Quick Top Right Icons Indicators (Polished micro-buttons)
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(EditorialSurfaceVariant)
                        .clickable { viewModel.showToast("المستند متصل بالخادم وتام المصادقة") },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "السحابية",
                        tint = EditorialPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(EditorialSurfaceVariant)
                        .clickable { viewModel.showToast("مرحبا ${viewModel.currentBranch.cashier}! صلاحية: محاسب كامل الفروع") },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = "خصائص دور الإذن",
                        tint = EditorialPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

// ==========================================
// BOTTOM NAVIGATION BAR (FLOATING DOCK RE-DESIGNED)
// ==========================================

@Composable
fun BottomM3NavBar(viewModel: FinancialViewModel) {
    // We elevate the bottom bar as a beautiful floating dock inside a small bottom container
    Surface(
        color = Color.Transparent,
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)), // Deep Premium Slate Dark Dock
            shape = RoundedCornerShape(24.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 10.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val navItems = listOf(
                    Triple("home", "الرئيسية", Icons.Default.Home),
                    Triple("entries", "القيود", Icons.Default.List),
                    Triple("invoices", "الفواتير", Icons.Default.Menu),
                    Triple("reports", "التقارير", Icons.Default.Info),
                    Triple("settings", "الإعدادات", Icons.Default.Settings)
                )

                navItems.forEach { (route, label, icon) ->
                    val isSelected = viewModel.currentTab == route

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (isSelected) Color(0xFF1E293B) else Color.Transparent)
                            .clickable { viewModel.currentTab = route }
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                            .testTag("nav_tab_$route"),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = icon,
                                contentDescription = label,
                                tint = if (isSelected) EditorialPrimary else Color(0xFF94A3B8),
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(
                                text = label,
                                fontSize = 11.sp,
                                fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Medium,
                                color = if (isSelected) Color.White else Color(0xFF64748B)
                            )
                        }
                    }
                }
            }
        }
    }
}

// ==========================================
// SCREEN 1: MAIN HOME DASHBOARD (RE-DESIGNED)
// ==========================================

@Composable
fun HomeScreen(viewModel: FinancialViewModel) {
    val branchCash = viewModel.branchBalances[viewModel.currentBranch]?.first ?: 0.0
    val branchBank = viewModel.branchBalances[viewModel.currentBranch]?.second ?: 0.0
    val totalBalance = branchCash + branchBank

    val df = DecimalFormat("#,##0.00")

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        // Balance Corporate Debit Card Mockup (Stunning metallic glassmorphic visual)
        item {
            Card(
                shape = RoundedCornerShape(24.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        BorderStroke(
                            1.dp,
                            Brush.linearGradient(
                                colors = listOf(
                                    Color.White.copy(alpha = 0.5f),
                                    Color.Transparent
                                )
                            )
                        ),
                        RoundedCornerShape(24.dp)
                    )
            ) {
                Column(
                    modifier = Modifier
                        .background(
                            Brush.linearGradient(
                                colors = listOf(
                                    Color(0xFF4F46E5), // Indigo Glow
                                    Color(0xFF3B82F6), // Electric Cobalt
                                    Color(0xFF8B5CF6)  // Cosmic Purple
                                )
                            )
                        )
                        .padding(24.dp)
                ) {
                    // Title and Active Status Badge
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "مستند الفرع المفعَّل",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White.copy(alpha = 0.7f)
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "إجمالي السيولة التشغيلية",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White
                            )
                        }

                        // Status Tag
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .background(Color.White.copy(alpha = 0.2f))
                                .padding(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF10B981)) // Glowing emerald
                                )
                                Text(
                                    text = "نشط الآن",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    // Primary Balance Display
                    Row(
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = df.format(totalBalance),
                            fontSize = 36.sp,
                            fontWeight = FontWeight.Black,
                            color = Color.White
                        )
                        Text(
                            text = "د.ل",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White.copy(alpha = 0.8f),
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(18.dp))
                    
                    // Card holder numbers hidden beautifully (Sleek layout)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "•••• •••• •••• 2026",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White.copy(alpha = 0.7f)
                        )
                        
                        // Small glowing circle representing secure bank chip/hologram
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color.White.copy(alpha = 0.15f))
                                .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.3f)), RoundedCornerShape(8.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "🔒",
                                fontSize = 14.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    // Cash & Bank sub items (Glassmorphic panes)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Surface(
                            color = Color.White.copy(alpha = 0.12f),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = "💵 نقد الصندوق",
                                    fontSize = 11.sp,
                                    color = Color.White.copy(alpha = 0.8f),
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = df.format(branchCash) + " د.ل",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Black,
                                    color = Color.White
                                )
                            }
                        }

                        Surface(
                            color = Color.White.copy(alpha = 0.12f),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = "🏦 الرصيد البنكي",
                                    fontSize = 11.sp,
                                    color = Color.White.copy(alpha = 0.8f),
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = df.format(branchBank) + " د.ل",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Black,
                                    color = Color.White
                                )
                            }
                        }
                    }
                }
            }
        }

        // Branch Manager & Primary Inventory category banner
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC)),
                border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .clip(CircleShape)
                            .background(EditorialPrimaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("👤", fontSize = 20.sp)
                    }

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "المدير المسؤول للفرع:",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.Gray
                            )
                            Text(
                                text = viewModel.currentBranch.managerAr,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = EditorialOnBackground
                            )
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "دائرة الأصناف والمخزون:",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.Gray
                            )
                            Text(
                                text = viewModel.currentBranch.inventoryCategory,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = EditorialPrimary
                            )
                        }
                    }
                }
            }
        }

        // Quick status badges (Re-designed with high-contrast borders and tags)
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Shift box
                val currentShift = viewModel.branchShifts[viewModel.currentBranch]
                val isShiftOpen = currentShift?.status == "Open"

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(18.dp))
                        .background(if (isShiftOpen) Color(0xFFECFDF5) else Color(0xFFFEF2F2))
                        .border(1.dp, if (isShiftOpen) Color(0xFFA7F3D0) else Color(0xFFFCA5A5), RoundedCornerShape(18.dp))
                        .padding(14.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(if (isShiftOpen) EditorialIncomeText else EditorialExpenseText)
                        )
                        Column {
                            Text(
                                text = "الوردية المفتوحة",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isShiftOpen) EditorialOnSurface else EditorialExpenseText
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "كاشير: ${currentShift?.cashierName ?: "غير معروف"}",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = EditorialOnBackground,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                // Fiscal Year Box
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color(0xFFF0F9FF))
                        .border(1.dp, Color(0xFFBAE6FD), RoundedCornerShape(18.dp))
                        .padding(14.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF0284C7))
                        )
                        Column {
                            Text(
                                text = "السنة المالية",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = EditorialOnSurface
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "نطاق مفتوح: 2026",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFF0369A1)
                            )
                        }
                    }
                }
            }
        }

        // Operational actions Grid (Fully upgraded layout)
        item {
            Column {
                Text(
                    text = "لوحة العمليات والتحكم",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Black,
                    color = EditorialOnBackground,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Action 1: New invoice
                    ActionIconButton(
                        title = "فاتورة جديدة",
                        icon = Icons.Default.Add,
                        bgGradient = Brush.linearGradient(colors = listOf(Color(0xFFEEF2FF), Color(0xFFE0E7FF))),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("action_new_invoice"),
                        onClick = { viewModel.isNewInvoiceOpen = true }
                    )

                    // Action 2: Manual journal entry
                    ActionIconButton(
                        title = "قيد محاسبي مالي",
                        icon = Icons.Default.List,
                        bgGradient = Brush.linearGradient(colors = listOf(Color(0xFFEFF6FF), Color(0xFFDBEAFE))),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("action_new_journal"),
                        onClick = { viewModel.isNewJournalOpen = true }
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Action 3: Inter-branch transfer
                    ActionIconButton(
                        title = "تحويل فروع",
                        icon = Icons.Default.Refresh,
                        bgGradient = Brush.linearGradient(colors = listOf(Color(0xFFFDF2F8), Color(0xFFFCE7F3))),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("action_transfer"),
                        onClick = { viewModel.isTransferOpen = true }
                    )

                    // Action 4: Daily closure
                    ActionIconButton(
                        title = "إغلاق اليوم المالي",
                        icon = Icons.Default.Lock,
                        bgGradient = Brush.linearGradient(colors = listOf(Color(0xFFFEF2F2), Color(0xFFFEE2E2))),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("action_closure"),
                        onClick = { viewModel.isClosureOpen = true }
                    )
                }
            }
        }

        // Recent Transaction list
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "آخر المعاملات والقيود المعتمدة لليوم",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Black,
                    color = EditorialOnBackground
                )
                Text(
                    text = "عرض الكل",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = EditorialPrimary,
                    modifier = Modifier
                        .clickable { viewModel.currentTab = "entries" }
                        .padding(4.dp)
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Show list of transactions filtered by branch
            val filteredEntries = viewModel.journalEntries
                .filter { it.branch == viewModel.currentBranch }
                .take(5)

            if (filteredEntries.isEmpty()) {
                Surface(
                    color = Color.White,
                    border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "لا توجد حركات مسجلة بالفرع لليوم",
                        fontSize = 13.sp,
                        color = Color.Gray,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(32.dp)
                    )
                }
            } else {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    shape = RoundedCornerShape(24.dp),
                    border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        filteredEntries.forEach { entry ->
                            RecentTransactionItem(entry) {
                                viewModel.activeTransactionForSidebar = entry
                            }
                        }
                    }
                }
            }
        }
    }
}

// ==========================================
// OPERATIONAL BUTTON INTERV-ACTION ENGINE (RE-DESIGNED)
// ==========================================

@Composable
fun ActionIconButton(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    bgGradient: Brush,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        color = Color.White,
        border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
        shape = RoundedCornerShape(20.dp),
        shadowElevation = 1.dp,
        modifier = modifier
            .clickable { onClick() }
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(bgGradient),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = EditorialPrimary,
                    modifier = Modifier.size(26.dp)
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = title,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = EditorialOnBackground,
                textAlign = TextAlign.Center
            )
        }
    }
}

// ==========================================
// COMPONENT: RECENT TRANSACTION ROW (RE-DESIGNED)
// ==========================================

@Composable
fun RecentTransactionItem(entry: JournalEntry, onClick: () -> Unit) {
    val df = DecimalFormat("#,##0.00")
    val isIncome = entry.referenceType in listOf("sale", "opening")

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Icon backdrop with gorgeous premium outline
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(EditorialSurfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = when (entry.referenceType) {
                        "sale" -> "🛒"
                        "purchase" -> "💳"
                        "transfer" -> "⟲"
                        "closure" -> "🔒"
                        else -> "⚙️"
                    },
                    fontSize = 18.sp
                )
            }

            // Desc info
            Column {
                Text(
                    text = entry.descriptionAr,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = EditorialOnBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 180.dp)
                )
                Spacer(modifier = Modifier.height(2.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(EditorialSurfaceVariant)
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = entry.entryNumber.split("-").lastOrNull() ?: entry.entryNumber,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = EditorialOnSurfaceVariant
                        )
                    }
                    Text(
                        text = "• ${entry.createdAt}",
                        fontSize = 10.sp,
                        color = Color.Gray
                    )
                }
            }
        }

        // Amount status indicator pill
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(if (isIncome) EditorialIncomeGreen else EditorialExpenseRed)
                .padding(horizontal = 10.dp, vertical = 4.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    text = if (isIncome) "+${df.format(entry.totalDebit)}" else "-${df.format(entry.totalDebit)}",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Black,
                    color = if (isIncome) EditorialIncomeText else EditorialExpenseText
                )
                Text(
                    text = "د.ل",
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isIncome) EditorialIncomeText else EditorialExpenseText
                )
            }
        }
    }
}

// ==========================================
// SCREEN 2: JOURNAL ENTRIES (DOUBLE-ENTRY)
// ==========================================

@Composable
fun JointEntriesScreen(viewModel: FinancialViewModel) {
    var searchFilter by remember { mutableStateOf("") }
    val df = DecimalFormat("#,##0.00")

    val filteredEntries = viewModel.journalEntries
        .filter { it.branch == viewModel.currentBranch }
        .filter {
            it.descriptionAr.contains(searchFilter) ||
                    it.entryNumber.contains(searchFilter) ||
                    it.referenceType.contains(searchFilter)
        }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Search header (Sleek modern search bar)
        OutlinedTextField(
            value = searchFilter,
            onValueChange = { searchFilter = it },
            placeholder = { Text("ابحث برقم القيد، البيان، أو تصنيف المرجعية...", fontSize = 12.sp, color = EditorialOnSurfaceVariant.copy(alpha = 0.7f)) },
            trailingIcon = { Icon(Icons.Default.Search, "بحث", tint = EditorialPrimary, modifier = Modifier.size(20.dp)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = Color.White,
                unfocusedContainerColor = Color.White,
                focusedBorderColor = EditorialPrimary,
                unfocusedBorderColor = Color(0xFFE2E8F0)
            )
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "سجل القيود المحاسبية المعمدة (${filteredEntries.size})",
                fontSize = 14.sp,
                fontWeight = FontWeight.Black,
                color = EditorialOnBackground
            )

            Button(
                onClick = { viewModel.isNewJournalOpen = true },
                colors = ButtonDefaults.buttonColors(containerColor = EditorialPrimary),
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(Icons.Default.Add, "قيد يدوي", tint = Color.White, modifier = Modifier.size(16.dp))
                    Text("قيد يدوي", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        if (filteredEntries.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("🔍", fontSize = 48.sp)
                    Text(
                        "لا توجد قيود مطابقة لاستعلامك",
                        color = Color.Gray,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(filteredEntries) { entry ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
                        shape = RoundedCornerShape(20.dp),
                        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.activeTransactionForSidebar = entry }
                    ) {
                        Column(modifier = Modifier.padding(18.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(EditorialPrimaryContainer)
                                        .padding(horizontal = 10.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        text = entry.entryNumber,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = EditorialOnPrimaryContainer
                                    )
                                }

                                Text(
                                    text = entry.date,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = EditorialOnSurfaceVariant
                                )
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            Text(
                                text = entry.descriptionAr,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = EditorialOnBackground
                            )

                            Spacer(modifier = Modifier.height(14.dp))

                            HorizontalDivider(color = Color(0xFFF1F5F9))

                            Spacer(modifier = Modifier.height(10.dp))

                            // Highlight primary dual lines
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "إجمالي المتوازن",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.Gray
                                )
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(EditorialPrimaryContainer)
                                        .padding(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = "${df.format(entry.totalDebit)} د.ل",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Black,
                                        color = EditorialOnPrimaryContainer
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            // Short representation of the accounts (fintech flow)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(EditorialSurfaceVariant, RoundedCornerShape(12.dp))
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "اتزان القيود",
                                    tint = EditorialIncomeText,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = entry.lines.joinToString(" ⇄ ") { it.accountName },
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = EditorialOnSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ==========================================
// SCREEN 3: INVOICES EXPLORER
// ==========================================

@Composable
fun InvoicesScreen(viewModel: FinancialViewModel) {
    var typeTab by remember { mutableStateOf("All") }
    val df = DecimalFormat("#,##0.00")

    val filteredInvoices = viewModel.invoices
        .filter { it.branch == viewModel.currentBranch }
        .filter {
            if (typeTab == "All") true
            else if (typeTab == "Sale") it.type == "Sale"
            else it.type == "Purchase"
        }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "الفواتير الحسابية المعتمدة",
                fontSize = 15.sp,
                fontWeight = FontWeight.Black,
                color = EditorialOnBackground
            )

            Button(
                onClick = { viewModel.isNewInvoiceOpen = true },
                colors = ButtonDefaults.buttonColors(containerColor = EditorialPrimary),
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(Icons.Default.Add, "إصدار فاتورة", tint = Color.White, modifier = Modifier.size(16.dp))
                    Text("إصدار فاتورة", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        // Horizontal toggle tabs for invoice types (Premium Segmented Control)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFFF1F5F9))
                .padding(4.dp)
        ) {
            val tabs = listOf("All" to "الكل", "Sale" to "المبيعات", "Purchase" to "المشتريات")
            tabs.forEach { (route, title) ->
                val active = typeTab == route
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (active) Color.White else Color.Transparent)
                        .clickable { typeTab = route }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = title,
                        fontSize = 12.sp,
                        fontWeight = if (active) FontWeight.ExtraBold else FontWeight.Bold,
                        color = if (active) EditorialPrimary else EditorialOnSurfaceVariant
                    )
                }
            }
        }

        if (filteredInvoices.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("📄", fontSize = 48.sp)
                    Text(
                        "لا توجد فواتير مسجلة في هذا التصنيف",
                        color = Color.Gray,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                items(filteredInvoices) { inv ->
                    val isSale = inv.type == "Sale"
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
                        shape = RoundedCornerShape(20.dp),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(18.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(if (isSale) EditorialIncomeGreen else EditorialExpenseRed)
                                            .padding(horizontal = 8.dp, vertical = 4.dp)
                                    ) {
                                        Text(
                                            text = if (isSale) "مبيعات" else "مشتريات",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Black,
                                            color = if (isSale) EditorialIncomeText else EditorialExpenseText
                                        )
                                    }
                                    Text(
                                        text = inv.invoiceNumber,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Black,
                                        color = EditorialOnSurfaceVariant
                                    )
                                }

                                Text(
                                    text = inv.date,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.Gray
                                )
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            Text(
                                text = inv.partyName,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Black,
                                color = EditorialOnBackground
                            )

                            // Item lines summary (Styled beautifully like a real receipt docket)
                            Spacer(modifier = Modifier.height(8.dp))
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(EditorialSurfaceVariant, RoundedCornerShape(12.dp))
                                    .padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                inv.lines.forEach { line ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = "${line.descriptionAr} × ${line.quantity}",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = EditorialOnSurfaceVariant
                                        )
                                        Text(
                                            text = "${df.format(line.totalAmount)} د.ل",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = EditorialOnBackground
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(14.dp))
                            HorizontalDivider(color = Color(0xFFF1F5F9))
                            Spacer(modifier = Modifier.height(10.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "طريقة الدفع:",
                                        fontSize = 12.sp,
                                        color = Color.Gray,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(Color(0xFFEFF6FF))
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            text = when (inv.paymentMethod) {
                                                "Cash" -> "نقداً"
                                                "Card" -> "بطاقة مصرفية"
                                                "Bank" -> "تحصيل بنكي"
                                                else -> "بالأجل"
                                            },
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = EditorialSecondary
                                        )
                                    }
                                }

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                                ) {
                                    Text(
                                        text = df.format(inv.totalAmount),
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.Black,
                                        color = EditorialOnBackground
                                    )
                                    Text("د.ل", fontSize = 10.sp, color = Color.Gray)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ==========================================
// SCREEN 4: ADVANCED BUSINESS REPORTS & CHARTS
// ==========================================

@Composable
fun AdvancedReportsScreen(viewModel: FinancialViewModel) {
    var selectedReportTab by remember { mutableStateOf("income") }
    var selectedPartyId by remember { mutableStateOf("1") }

    val df = DecimalFormat("#,##0.00")

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "التقارير المحاسبية والتحليلات البيانية",
            fontSize = 16.sp,
            fontWeight = FontWeight.Black,
            color = EditorialOnBackground
        )

        // Sub horizontal tab - Beautiful Modern Custom Segmented Control
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFFF1F5F9))
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            val tabs = listOf(
                "income" to "قائمة الدخل",
                "balance" to "الميزانية",
                "flow" to "السيولة",
                "party" to "كشف حساب"
            )
            tabs.forEach { (route, title) ->
                val active = selectedReportTab == route
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (active) EditorialPrimary else Color.Transparent)
                        .clickable { selectedReportTab = route }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = title,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = if (active) Color.White else EditorialOnSurfaceVariant
                    )
                }
            }
        }

        when (selectedReportTab) {
            "income" -> {
                // INCOME STATEMENT (STATEMENT OF PROFIT OR LOSS)
                val salesRevenue = viewModel.invoices
                    .filter { it.branch == viewModel.currentBranch && it.type == "Sale" }
                    .sumOf { it.totalAmount }

                val purchaseCost = viewModel.invoices
                    .filter { it.branch == viewModel.currentBranch && it.type == "Purchase" }
                    .sumOf { it.totalAmount }

                val netProfit = salesRevenue - purchaseCost

                Card(
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
                    shape = RoundedCornerShape(24.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(22.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(
                            text = "قائمة الأرباح والخسائر للفرع",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Black,
                            color = EditorialOnBackground
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("📈", fontSize = 16.sp)
                                Text("إجمالي المبيعات المحققة", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.Gray)
                            }
                            Text(
                                text = "${df.format(salesRevenue)} د.ل",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Black,
                                color = EditorialIncomeText
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("📉", fontSize = 16.sp)
                                Text("تكلفة السلع والمشتريات", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.Gray)
                            }
                            Text(
                                text = "${df.format(purchaseCost)} د.ل",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Black,
                                color = EditorialExpenseText
                            )
                        }

                        HorizontalDivider(color = Color(0xFFF1F5F9))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "صافي الربح التشغيلي",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Black,
                                color = EditorialOnBackground
                            )
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (netProfit >= 0.0) EditorialIncomeGreen else EditorialExpenseRed)
                                    .padding(horizontal = 14.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = "${df.format(netProfit)} د.ل",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Black,
                                    color = if (netProfit >= 0.0) EditorialIncomeText else EditorialExpenseText
                                )
                            }
                        }
                    }
                }
            }

            "balance" -> {
                // BALANCE SHEET (STATEMENT OF FINANCIAL POSITION)
                val branchCash = viewModel.branchBalances[viewModel.currentBranch]?.first ?: 0.0
                val branchBank = viewModel.branchBalances[viewModel.currentBranch]?.second ?: 0.0
                val debtors = viewModel.parties.filter { it.type == "Customer" }.sumOf { it.balance }
                val creditors = viewModel.parties.filter { it.type == "Supplier" }.sumOf { it.balance }

                val assetsTotal = branchCash + branchBank + debtors
                val liabilitiesTotal = creditors

                Card(
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
                    shape = RoundedCornerShape(24.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(22.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Text(
                            text = "التقرير المالي للمركز المالي (الميزانية)",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Black,
                            color = EditorialOnBackground
                        )

                        Text(
                            text = "الأصول النقدية والمدينة (Assets)",
                            fontSize = 12.sp,
                            color = EditorialPrimary,
                            fontWeight = FontWeight.Black
                        )

                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("خزينة الصندوق الجاري", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.Gray)
                                Text("${df.format(branchCash)} د.ل", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = EditorialOnBackground)
                            }
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("الأرصدة البنكية والبطاقات", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.Gray)
                                Text("${df.format(branchBank)} د.ل", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = EditorialOnBackground)
                            }
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("العملاء والذمم المدينة", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.Gray)
                                Text("${df.format(debtors)} د.ل", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = EditorialOnBackground)
                            }
                        }

                        HorizontalDivider(color = Color(0xFFF1F5F9))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("مجموع قيمة الأصول", fontSize = 13.sp, fontWeight = FontWeight.Black, color = EditorialOnBackground)
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(EditorialPrimaryContainer)
                                    .padding(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = "${df.format(assetsTotal)} د.ل",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Black,
                                    color = EditorialOnPrimaryContainer
                                )
                            }
                        }
                    }
                }
            }

            "flow" -> {
                // FLOW ANALYSIS WITH VISUAL PROGRESS GRAPH & MONEY LIFECYCLE DISTRIBUTION
                val inflows = viewModel.moneyFlows.filter { it.branch == viewModel.currentBranch && it.direction == "Inflow" }.sumOf { it.amount }
                val outflows = viewModel.moneyFlows.filter { it.branch == viewModel.currentBranch && it.direction == "Outflow" }.sumOf { it.amount }

                val salesCash = viewModel.invoices
                    .filter { it.branch == viewModel.currentBranch && it.type == "Sale" && it.paymentMethod == "Cash" }
                    .sumOf { it.totalAmount }

                val salesCard = viewModel.invoices
                    .filter { it.branch == viewModel.currentBranch && it.type == "Sale" && (it.paymentMethod == "Card" || it.paymentMethod == "Bank") }
                    .sumOf { it.totalAmount }

                val collections = viewModel.invoices
                    .filter { it.branch == viewModel.currentBranch && it.type == "Sale" && it.paymentMethod == "Credit" }
                    .sumOf { it.paidAmount }

                val purchases = viewModel.invoices
                    .filter { it.branch == viewModel.currentBranch && it.type == "Purchase" }
                    .sumOf { it.totalAmount }

                // Default expenses or manual journal expense entries
                val expenses = viewModel.journalEntries
                    .filter { it.branch == viewModel.currentBranch && it.referenceType == "adjustment" }
                    .sumOf { it.totalDebit } * 0.4

                val lifecycleData = com.example.modules.fileapp.MoneyLifecycleManager.calculateLifecycleDistribution(
                    totalSalesCash = if (salesCash > 0) salesCash else 14850.00,
                    totalSalesCard = if (salesCard > 0) salesCard else 9210.00,
                    totalCollections = if (collections > 0) collections else 3800.00,
                    totalExpenses = if (expenses > 0) expenses else 2400.00,
                    totalPurchases = if (purchases > 0) purchases else 6400.00
                )

                Card(
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
                    shape = RoundedCornerShape(24.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(22.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(
                            text = "تحليل الدورة التشغيلية لدورة حياة حركة الأموال لليوم",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Black,
                            color = EditorialOnBackground
                        )

                        // Progress representation bar chart
                        val totalInAndOut = (inflows + outflows).coerceAtLeast(1.0)
                        val inflowRatio = (inflows / totalInAndOut).toFloat()

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(16.dp)
                                .clip(CircleShape)
                                .background(EditorialExpenseRed)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .fillMaxWidth(inflowRatio)
                                    .background(EditorialPrimary)
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(EditorialPrimary))
                                Text(
                                    text = "تدفقات داخلة (وارد): ${df.format(inflows)} د.ل (${(inflowRatio * 100).toInt()}%)",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = EditorialOnSurfaceVariant
                                )
                            }

                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(EditorialExpenseRed))
                                Text(
                                    text = "تدفقات خارجة (صادر): ${df.format(outflows)} د.ل (${100 - (inflowRatio * 100).toInt()}%)",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = EditorialOnSurfaceVariant
                                )
                            }
                        }

                        HorizontalDivider(color = Color(0xFFF1F5F9))

                        Text(
                            text = "المسارات التحليلية لحركة الأموال (من أين وإلى أين تذهب السيولة):",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Black,
                            color = EditorialOnBackground
                        )

                        // List of modular lifecycle analyses
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            lifecycleData.forEach { analysis ->
                                val colorSchemeHex = Color(android.graphics.Color.parseColor(analysis.colorHex))
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(colorSchemeHex))
                                            Text(analysis.title, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = EditorialOnBackground)
                                            Box(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(4.dp))
                                                    .background(if (analysis.category == "Inflow") EditorialIncomeGreen else EditorialExpenseRed)
                                                    .padding(horizontal = 4.dp, vertical = 1.dp)
                                            ) {
                                                Text(
                                                    text = if (analysis.category == "Inflow") "وارد" else "خارج",
                                                    fontSize = 8.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = if (analysis.category == "Inflow") EditorialIncomeText else EditorialExpenseText
                                                )
                                            }
                                        }
                                        Text(analysis.getFormattedAmount(), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = EditorialOnBackground)
                                    }

                                    // Small bar represent for item percentage
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(4.dp)
                                            .clip(CircleShape)
                                            .background(Color(0xFFF1F5F9))
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxHeight()
                                                .fillMaxWidth(analysis.percentage / 100f)
                                                .background(colorSchemeHex)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            "party" -> {
                // PARTY LEDGER EXPLORER
                var expandedDropdown by remember { mutableStateOf(false) }
                val selectedParty = viewModel.parties.find { it.id == selectedPartyId } ?: viewModel.parties.first()

                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(modifier = Modifier.fillMaxWidth()) {
                        Button(
                            onClick = { expandedDropdown = true },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF1F5F9)),
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(selectedParty.nameAr, color = EditorialOnBackground, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                Icon(Icons.Default.ArrowDropDown, "dropdown", tint = EditorialOnBackground)
                            }
                        }

                        DropdownMenu(
                            expanded = expandedDropdown,
                            onDismissRequest = { expandedDropdown = false },
                            modifier = Modifier
                                .fillMaxWidth(0.9f)
                                .background(Color.White)
                        ) {
                            viewModel.parties.forEach { p ->
                                DropdownMenuItem(
                                    text = { Text(p.nameAr, fontSize = 12.sp, fontWeight = FontWeight.Bold) },
                                    onClick = {
                                        selectedPartyId = p.id
                                        expandedDropdown = false
                                    }
                                )
                            }
                        }
                    }

                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
                        shape = RoundedCornerShape(24.dp),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(22.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("رقم الكود المالي للعميل", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.Gray)
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(EditorialSurfaceVariant)
                                        .padding(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = selectedParty.code,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = EditorialOnBackground
                                    )
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("الرصيد التراكمي المتبقي", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.Gray)
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (selectedParty.balance >= 0.0) EditorialIncomeGreen else EditorialExpenseRed)
                                        .padding(horizontal = 10.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        text = "${df.format(selectedParty.balance)} د.ل",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Black,
                                        color = if (selectedParty.balance >= 0.0) EditorialIncomeText else EditorialExpenseText
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(6.dp))
                            HorizontalDivider(color = Color(0xFFF1F5F9))

                            Text("كشف الحساب التفصيلي للعمليات المالية المباشرة", fontSize = 12.sp, fontWeight = FontWeight.Black, color = EditorialOnBackground)

                            val partyInvoices = viewModel.invoices.filter {
                                it.partyName == selectedParty.nameAr || 
                                selectedParty.nameAr.contains(it.partyName) || 
                                it.partyName.contains(selectedParty.nameAr)
                            }

                            if (partyInvoices.isEmpty()) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(Color(0xFFF8FAFC), RoundedCornerShape(12.dp))
                                        .padding(16.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "لا توجد حركات مالية أو فواتير مسجلة لهذا الطرف بعد.",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.Gray,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            } else {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    partyInvoices.forEach { invoice ->
                                        val isSale = invoice.type == "Sale"
                                        Card(
                                            colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC)),
                                            border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
                                            shape = RoundedCornerShape(16.dp),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(12.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                                            ) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(34.dp)
                                                        .clip(CircleShape)
                                                        .background(if (isSale) EditorialIncomeGreen else EditorialExpenseRed),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Text(if (isSale) "📥" else "📤", fontSize = 14.sp)
                                                }

                                                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                                    Row(
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                                    ) {
                                                        Text(
                                                            text = invoice.invoiceNumber,
                                                            fontSize = 11.sp,
                                                            fontWeight = FontWeight.Black,
                                                            color = EditorialOnBackground
                                                        )
                                                        Box(
                                                            modifier = Modifier
                                                                .clip(RoundedCornerShape(4.dp))
                                                                .background(if (isSale) EditorialPrimary else Color.DarkGray)
                                                                .padding(horizontal = 4.dp, vertical = 1.dp)
                                                        ) {
                                                            Text(
                                                                text = if (isSale) "مبيعات" else "مشتريات",
                                                                fontSize = 8.sp,
                                                                color = Color.White,
                                                                fontWeight = FontWeight.Bold
                                                            )
                                                        }
                                                    }
                                                    Text(
                                                        text = "بتاريخ: ${invoice.date} • ${invoice.paymentMethod}",
                                                        fontSize = 10.sp,
                                                        color = Color.Gray,
                                                        fontWeight = FontWeight.SemiBold
                                                    )
                                                }

                                                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                                    Text(
                                                        text = "${df.format(invoice.totalAmount)} د.ل",
                                                        fontSize = 12.sp,
                                                        fontWeight = FontWeight.Black,
                                                        color = if (isSale) EditorialIncomeText else EditorialExpenseText
                                                    )
                                                    Box(
                                                        modifier = Modifier
                                                            .clip(RoundedCornerShape(4.dp))
                                                            .background(if (invoice.status == "Paid") EditorialIncomeGreen else Color(0xFFFEF3C7))
                                                            .padding(horizontal = 4.dp, vertical = 1.dp)
                                                    ) {
                                                        Text(
                                                            text = if (invoice.status == "Paid") "مسدد" else "آجل",
                                                            fontSize = 8.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            color = if (invoice.status == "Paid") EditorialIncomeText else Color(0xFFB45309)
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "كل قيود ومطابقات الحركات المالية مؤمنة بنظام القيد المحاسبي المزدوج ومرحلة دفترياً بنجاح.",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.Gray
                            )
                        }
                    }
                }
            }
        }
    }
}

// ==========================================
// OVERLAY DIALOG 1: NEW INVOICE DIALOG
// ==========================================

@Composable
fun NewInvoiceDialog(viewModel: FinancialViewModel, onDismiss: () -> Unit) {
    var invoiceType by remember { mutableStateOf("Sale") }
    var selectedPartyName by remember { mutableStateOf(viewModel.parties.first().nameAr) }
    var paymentMethod by remember { mutableStateOf("Cash") }
    var itemDescription by remember { mutableStateOf("") }
    var quantityInput by remember { mutableStateOf("1") }
    var priceInput by remember { mutableStateOf("") }
    var discountInput by remember { mutableStateOf("0") }
    var notesInput by remember { mutableStateOf("فاتورة مصدرة بالفرع") }

    var dropdownExpanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(
                onClick = {
                    val qty = quantityInput.toIntOrNull() ?: 1
                    val price = priceInput.toDoubleOrNull() ?: 0.0
                    val disc = discountInput.toDoubleOrNull() ?: 0.0

                    if (itemDescription.isBlank()) {
                        viewModel.showToast("الرجاء إدخال اسم السلعة/الخدمة")
                        return@Button
                    }
                    if (price <= 0.0) {
                        viewModel.showToast("الرجاء إدخال سعر صحيح")
                        return@Button
                    }

                    val success = viewModel.addInvoice(
                        type = invoiceType,
                        partyName = selectedPartyName,
                        paymentMethod = paymentMethod,
                        productName = itemDescription,
                        quantity = qty,
                        unitPrice = price,
                        discountPercent = disc,
                        taxPercent = 5.0,
                        notes = notesInput
                    )
                    if (success) onDismiss()
                },
                colors = ButtonDefaults.buttonColors(containerColor = EditorialPrimary)
            ) {
                Text("تأكيد وحفظ القيد")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("إلغاء", color = Color.Gray) }
        },
        title = {
            Text(
                "إصدار فاتورة جديدة للفرع",
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                color = EditorialOnBackground,
                textAlign = TextAlign.Right,
                modifier = Modifier.fillMaxWidth()
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Invoice Type Choose
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(EditorialSurfaceVariant)
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .background(if (invoiceType == "Sale") EditorialPrimary else Color.Transparent)
                            .clickable { invoiceType = "Sale" }
                            .padding(8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("فاتورة مبيعات", color = if (invoiceType == "Sale") Color.White else Color.DarkGray, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .background(if (invoiceType == "Purchase") EditorialPrimary else Color.Transparent)
                            .clickable { invoiceType = "Purchase" }
                            .padding(8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("فاتورة مشتريات", color = if (invoiceType == "Purchase") Color.White else Color.DarkGray, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }

                // Party selection dropdown
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("الطرف المتعامل (العميل أو المورد):", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        Text(
                            text = "+ إضافة عميل/مورد جديد",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = EditorialPrimary,
                            modifier = Modifier.clickable { viewModel.isNewPartyOpen = true }
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Box(modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = { dropdownExpanded = true },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(selectedPartyName, fontSize = 12.sp, color = EditorialOnBackground)
                        }

                        DropdownMenu(
                            expanded = dropdownExpanded,
                            onDismissRequest = { dropdownExpanded = false },
                            modifier = Modifier.fillMaxWidth().background(Color.White)
                        ) {
                            viewModel.parties.forEach { p ->
                                DropdownMenuItem(
                                    text = { Text(p.nameAr, fontSize = 12.sp) },
                                    onClick = {
                                        selectedPartyName = p.nameAr
                                        dropdownExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }

                // Payment Method
                Column {
                    Text("طريقة تحصيل الأموال:", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("Cash" to "نقداً", "Card" to "بطاقة", "Bank" to "حساب مصرفي", "Credit" to "بالأجل").forEach { (method, title) ->
                            val active = paymentMethod == method
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (active) EditorialPrimaryContainer else EditorialSurfaceVariant)
                                    .clickable { paymentMethod = method }
                                    .padding(vertical = 6.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(title, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = if (active) EditorialOnPrimaryContainer else Color.DarkGray)
                            }
                        }
                    }
                }

                // Item description
                OutlinedTextField(
                    value = itemDescription,
                    onValueChange = { itemDescription = it },
                    label = { Text("البيان والسلعة المباعة / المشتراة", fontSize = 11.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Price
                    OutlinedTextField(
                        value = priceInput,
                        onValueChange = { priceInput = it },
                        label = { Text("سعر الوحدة د.ل", fontSize = 11.sp) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )

                    // Quantity
                    OutlinedTextField(
                        value = quantityInput,
                        onValueChange = { quantityInput = it },
                        label = { Text("الكمية عدد", fontSize = 11.sp) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Discount
                    OutlinedTextField(
                        value = discountInput,
                        onValueChange = { discountInput = it },
                        label = { Text("نسبة الخصم %", fontSize = 11.sp) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )

                    // Tax representation info
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .align(Alignment.CenterVertically)
                            .clip(RoundedCornerShape(8.dp))
                            .background(EditorialSurfaceVariant)
                            .padding(10.dp)
                    ) {
                        Text("الضريبة المضافة: 5%", fontSize = 10.sp, color = EditorialPrimary, fontWeight = FontWeight.Bold)
                    }
                }

                // Notes
                OutlinedTextField(
                    value = notesInput,
                    onValueChange = { notesInput = it },
                    label = { Text("ملاحظات إضافية على المعاملة", fontSize = 11.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    )
}

// ==========================================
// OVERLAY DIALOG 1B: NEW CUSTOMER OR SUPPLIER DIALOG
// ==========================================

@Composable
fun NewPartyDialog(viewModel: FinancialViewModel, onDismiss: () -> Unit) {
    var partyNameInput by remember { mutableStateOf("") }
    var partyTypeInput by remember { mutableStateOf("Customer") } // "Customer" or "Supplier"
    var partyPhoneInput by remember { mutableStateOf("") }
    var initialBalanceInput by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(
                onClick = {
                    val initialBal = initialBalanceInput.toDoubleOrNull() ?: 0.0
                    val isCust = partyTypeInput == "Customer"
                    // Signed Balance: positive debit balance for Customer, negative credit balance for Supplier
                    val finalBal = if (isCust) initialBal else -initialBal
                    val success = viewModel.addParty(
                        name = partyNameInput.trim(),
                        type = partyTypeInput,
                        phone = partyPhoneInput.trim(),
                        initialBalance = finalBal
                    )
                    if (success) {
                        onDismiss()
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = EditorialPrimary),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.testTag("confirm_add_party_btn")
            ) {
                Text("إضافة وحفظ", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("إلغاء", color = Color.Gray, fontSize = 12.sp)
            }
        },
        title = {
            Text(
                text = "إضافة عميل أو مورد جديد",
                fontSize = 15.sp,
                fontWeight = FontWeight.Black,
                color = EditorialOnBackground,
                textAlign = TextAlign.Right,
                modifier = Modifier.fillMaxWidth()
            )
        },
        containerColor = Color.White,
        shape = RoundedCornerShape(24.dp),
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "أدخل بيانات العميل أو المورد الجديد وسيتم ربطه تلقائياً بالفرع المفتوح ${viewModel.currentBranch.nameAr}.",
                    fontSize = 11.sp,
                    color = Color.Gray
                )

                // Segmented Selector for Type (Customer/Supplier)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("نوع الطرف:", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(EditorialSurfaceVariant)
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .background(if (partyTypeInput == "Customer") EditorialPrimary else Color.Transparent)
                                .clickable { partyTypeInput = "Customer" }
                                .padding(8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("عميل (ذمم مدينة)", color = if (partyTypeInput == "Customer") Color.White else Color.DarkGray, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .background(if (partyTypeInput == "Supplier") EditorialPrimary else Color.Transparent)
                                .clickable { partyTypeInput = "Supplier" }
                                .padding(8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("مورد (ذمم دائنة)", color = if (partyTypeInput == "Supplier") Color.White else Color.DarkGray, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                // Party Name
                OutlinedTextField(
                    value = partyNameInput,
                    onValueChange = { partyNameInput = it },
                    label = { Text("الاسم الكامل للعميل أو الشركة", fontSize = 11.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("party_name_field")
                )

                // Phone
                OutlinedTextField(
                    value = partyPhoneInput,
                    onValueChange = { partyPhoneInput = it },
                    label = { Text("رقم الهاتف / الاتصال", fontSize = 11.sp) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("party_phone_field")
                )

                // Initial Balance
                OutlinedTextField(
                    value = initialBalanceInput,
                    onValueChange = { initialBalanceInput = it },
                    label = { Text("الرصيد الافتتاحي المقدر (د.ل)", fontSize = 11.sp) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("party_balance_field")
                )
            }
        }
    )
}

// ==========================================
// OVERLAY DIALOG 2: MANUAL JOURNAL ENTRY DIALOG
// ==========================================

@Composable
fun NewJournalEntryDialog(viewModel: FinancialViewModel, onDismiss: () -> Unit) {
    var entryDescription by remember { mutableStateOf("") }
    var debitAccountCode by remember { mutableStateOf("1111") } // cash
    var creditAccountCode by remember { mutableStateOf("4100") } // sales revenue
    var amountValue by remember { mutableStateOf("") }

    val debitAccount = viewModel.systemAccounts.find { it.code == debitAccountCode } ?: viewModel.systemAccounts.first()
    val creditAccount = viewModel.systemAccounts.find { it.code == creditAccountCode } ?: viewModel.systemAccounts.first()

    val balanceMatched = amountValue.toDoubleOrNull() ?: 0.0

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(
                onClick = {
                    val amount = amountValue.toDoubleOrNull() ?: 0.0
                    if (entryDescription.isBlank()) {
                        viewModel.showToast("الرجاء إدخال شرح سياق القيد!")
                        return@Button
                    }
                    if (debitAccountCode == creditAccountCode) {
                        viewModel.showToast("لا يمكن أن يكون حساب المدين والدائن متطابقين!")
                        return@Button
                    }

                    val success = viewModel.addManualJournalEntry(
                        description = entryDescription,
                        debitAccount = debitAccount,
                        debitAmount = amount,
                        creditAccount = creditAccount,
                        creditAmount = amount
                    )
                    if (success) onDismiss()
                },
                colors = ButtonDefaults.buttonColors(containerColor = EditorialPrimary)
            ) {
                Text("ترحيل القيد")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("إلغاء", color = Color.Gray) }
        },
        title = {
            Text(
                "قيد محاسبي مزدوج جديد",
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                textAlign = TextAlign.Right,
                modifier = Modifier.fillMaxWidth()
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Desc
                OutlinedTextField(
                    value = entryDescription,
                    onValueChange = { entryDescription = it },
                    label = { Text("شرح سياق القيد Ledger Memo", fontSize = 11.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // Debit Choose Selector
                Column {
                    Text("الجانب المدين المدفوع Dr (+):", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        viewModel.systemAccounts.take(4).forEach { acct ->
                            val isSelected = debitAccountCode == acct.code
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (isSelected) EditorialPrimaryContainer else EditorialSurfaceVariant)
                                    .clickable { debitAccountCode = acct.code }
                                    .padding(vertical = 6.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(acct.nameAr.take(8), fontSize = 9.sp, fontWeight = FontWeight.Bold, color = if (isSelected) EditorialOnPrimaryContainer else Color.DarkGray)
                            }
                        }
                    }
                }

                // Credit Choose Selector
                Column {
                    Text("الجانب الدائن المقابل Cr (-):", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        viewModel.systemAccounts.takeLast(4).forEach { acct ->
                            val isSelected = creditAccountCode == acct.code
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (isSelected) EditorialPrimaryContainer else EditorialSurfaceVariant)
                                    .clickable { creditAccountCode = acct.code }
                                    .padding(vertical = 6.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(acct.nameAr.take(8), fontSize = 9.sp, fontWeight = FontWeight.Bold, color = if (isSelected) EditorialOnPrimaryContainer else Color.DarkGray)
                            }
                        }
                    }
                }

                // Amount
                OutlinedTextField(
                    value = amountValue,
                    onValueChange = { amountValue = it },
                    label = { Text("المبلغ المالي الموازن د.ل", fontSize = 11.sp) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // Balanced real check tag
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (balanceMatched > 0.0) EditorialIncomeGreen.copy(alpha = 0.4f) else EditorialExpenseRed)
                        .padding(10.dp)
                ) {
                    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = if (balanceMatched > 0.0) "✓ القيد المحاسبي متوازن تماماً" else "⚠ بانتظار إدخال قيمة صحيحة",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (balanceMatched > 0.0) EditorialIncomeText else EditorialExpenseText
                        )
                        Text(
                            text = "${balanceMatched} DR = ${balanceMatched} CR",
                            fontSize = 11.sp,
                            color = Color.DarkGray
                        )
                    }
                }
            }
        }
    )
}

// ==========================================
// OVERLAY DIALOG 3: INTER-BRANCH TRANSFER
// ==========================================

@Composable
fun InterBranchTransferDialog(viewModel: FinancialViewModel, onDismiss: () -> Unit) {
    var targetBranchObj by remember { mutableStateOf(Branch.TRIPOLI) }
    var transferType by remember { mutableStateOf("Box Cash") }
    var amountValue by remember { mutableStateOf("") }
    var referenceInput by remember { mutableStateOf("TR-${System.currentTimeMillis() % 10000}") }
    var notesInput by remember { mutableStateOf("تحويل نقدي بين فروع المؤسسة") }

    var expandedBranchDrop by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(
                onClick = {
                    val amount = amountValue.toDoubleOrNull() ?: 0.0
                    val success = viewModel.addInterbranchTransfer(
                        targetBranch = targetBranchObj,
                        transferType = transferType,
                        amount = amount,
                        referenceNum = referenceInput,
                        notes = notesInput
                    )
                    if (success) onDismiss()
                },
                colors = ButtonDefaults.buttonColors(containerColor = EditorialPrimary)
            ) {
                Text("إرسال الحوالة")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("إلغاء", color = Color.Gray) }
        },
        title = {
            Text(
                "تحويل مالي بين الفروع والوحدات",
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                textAlign = TextAlign.Right,
                modifier = Modifier.fillMaxWidth()
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // Target Branch Selection
                Column {
                    Text("الفرع المستهدف للاستلام:", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Box(modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = { expandedBranchDrop = true },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(targetBranchObj.nameAr, fontSize = 12.sp, color = EditorialOnBackground)
                        }

                        DropdownMenu(
                            expanded = expandedBranchDrop,
                            onDismissRequest = { expandedBranchDrop = false },
                            modifier = Modifier.fillMaxWidth().background(Color.White)
                        ) {
                            Branch.values().filter { it != viewModel.currentBranch }.forEach { br ->
                                DropdownMenuItem(
                                    text = { Text(br.nameAr) },
                                    onClick = {
                                        targetBranchObj = br
                                        expandedBranchDrop = false
                                    }
                                )
                            }
                        }
                    }
                }

                // Transfer mechanism type choosing
                Column {
                    Text("طبيعة الرصيد المحول:", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("Box Cash" to "نقد الصندوق", "Bank account" to "الرصيد البنكي").forEach { (type, text) ->
                            val isSelected = transferType == type
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isSelected) EditorialPrimaryContainer else EditorialSurfaceVariant)
                                    .clickable { transferType = type }
                                    .padding(10.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(text, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = if (isSelected) EditorialOnPrimaryContainer else Color.DarkGray)
                            }
                        }
                    }
                }

                // Amount
                OutlinedTextField(
                    value = amountValue,
                    onValueChange = { amountValue = it },
                    label = { Text("المبلغ د.ل", fontSize = 11.sp) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // Reference
                OutlinedTextField(
                    value = referenceInput,
                    onValueChange = { referenceInput = it },
                    label = { Text("الرقم المرجعي أو المستند", fontSize = 11.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // Memo
                OutlinedTextField(
                    value = notesInput,
                    onValueChange = { notesInput = it },
                    label = { Text("شرح التحويل", fontSize = 11.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    )
}

// ==========================================
// OVERLAY DIALOG 4: DAILY CLOSURE PROCESS
// ==========================================

@Composable
fun DailyClosureDialog(viewModel: FinancialViewModel, onDismiss: () -> Unit) {
    val branchCash = viewModel.branchBalances[viewModel.currentBranch]?.first ?: 0.0
    val df = DecimalFormat("#,##0.00")

    var actualCashInput by remember { mutableStateOf("") }
    var notesInput by remember { mutableStateOf("تم مطابقة الأرصدة النقدية والبنكية واقفال الصناديق بالكامل.") }

    var stepCashMatchConfirmed by remember { mutableStateOf(false) }
    var stepRegistersClearConfirmed by remember { mutableStateOf(false) }

    val actualClosingValue = actualCashInput.toDoubleOrNull() ?: branchCash
    val discrepancy = actualClosingValue - branchCash

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(
                onClick = {
                    if (!stepCashMatchConfirmed || !stepRegistersClearConfirmed) {
                        viewModel.showToast("الرجاء مراجعة وتأكيد كافة البنود المالية لضمان سلامة الإغلاق")
                        return@Button
                    }
                    val success = viewModel.runDailyClosure(
                        actualClosing = actualClosingValue,
                        notes = notesInput
                    )
                    if (success) onDismiss()
                },
                colors = ButtonDefaults.buttonColors(containerColor = EditorialPrimary)
            ) {
                Text("إتمام الإغلاق المالي")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("إلغاء", color = Color.Gray) }
        },
        title = {
            Text(
                "الإغلاق المالي اليومي وتجميد القيود",
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                textAlign = TextAlign.Right,
                modifier = Modifier.fillMaxWidth()
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // Expected values info tag
                Surface(
                    color = EditorialPrimaryContainer,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("القيم المتوقعة ترحيلها حسب القيود الآلية:", fontSize = 10.sp, color = EditorialOnPrimaryContainer.copy(alpha = 0.8f))
                        Text(
                            text = "الرصيد الدفتري الحالي: ${df.format(branchCash)} د.ل",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Black,
                            color = EditorialOnPrimaryContainer
                        )
                    }
                }

                // Checkbox controls
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Checkbox(checked = stepCashMatchConfirmed, onCheckedChange = { stepCashMatchConfirmed = it })
                    Text("مطابقة وتجميد خزينة الفرع الفعلية", fontSize = 11.sp, color = EditorialOnBackground)
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Checkbox(checked = stepRegistersClearConfirmed, onCheckedChange = { stepRegistersClearConfirmed = it })
                    Text("إقفال وردية الكاشير الحالية وترحيل صافي الدخل", fontSize = 11.sp, color = EditorialOnBackground)
                }

                // Actual Cash input
                OutlinedTextField(
                    value = actualCashInput,
                    onValueChange = { actualCashInput = it },
                    label = { Text("المقدار الفعلي المتواجد بالصندوق د.ل", fontSize = 11.sp) },
                    placeholder = { Text(df.format(branchCash), fontSize = 11.sp) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // Discrepancy indicator highlights
                val isNegative = discrepancy < 0.0
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (discrepancy == 0.0) EditorialIncomeGreen.copy(alpha = 0.4f) else EditorialExpenseRed)
                        .padding(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("فرق الإقفال اليومي:", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = if (discrepancy == 0.0) EditorialIncomeText else EditorialExpenseText)
                        Text(
                            text = "${df.format(discrepancy)} د.ل (${if (discrepancy == 0.0) "متطابق" else if (isNegative) "عجز مالي" else "فائض"})",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (discrepancy == 0.0) EditorialIncomeText else EditorialExpenseText
                        )
                    }
                }

                // Notes
                OutlinedTextField(
                    value = notesInput,
                    onValueChange = { notesInput = it },
                    label = { Text("ملاحظات تسوية الفروقات والإغلاق", fontSize = 11.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    )
}

// ==========================================
// COMPONENT 5: TRANSACTION DETAIL SIDEBAR & LEDGER AUDIT
// ==========================================

@Composable
fun AuditTrailSidebar(
    entry: JournalEntry,
    onClose: () -> Unit,
    viewModel: FinancialViewModel
) {
    val df = DecimalFormat("#,##0.00")

    // Semi-transparent side modal window
    Surface(
        color = Color.White,
        tonalElevation = 8.dp,
        border = BorderStroke(1.dp, Color(0xFFECEFF1)),
        modifier = Modifier
            .fillMaxHeight()
            .fillMaxWidth(0.85f)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "تفاصيل وتتبع القيد المحاسبي",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Black,
                    color = EditorialOnBackground
                )

                IconButton(onClick = onClose) {
                    Icon(Icons.Default.Close, "إغلاق التفاصيل")
                }
            }

            HorizontalDivider()

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(EditorialPrimaryContainer)
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = entry.entryNumber,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = EditorialOnPrimaryContainer
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(EditorialIncomeGreen)
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "مرحّل Posted",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = EditorialIncomeText
                    )
                }
            }

            // Description details
            Text(
                text = entry.descriptionAr,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = EditorialOnBackground
            )

            // Dynamic General Ledger lines
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("شجرة كيد القيد المزدوج المتوازن (Journal Ledger Lines):", fontSize = 10.sp, color = Color.Gray, fontWeight = FontWeight.Bold)

                entry.lines.forEach { line ->
                    Surface(
                        color = EditorialSurfaceVariant,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(line.accountName, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                Text("كود: ${line.accountCode}", fontSize = 9.sp, color = Color.Gray)
                            }

                            Spacer(modifier = Modifier.height(4.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                if (line.debit > 0.0) {
                                    Text("مدين Dr (+)", fontSize = 10.sp, color = Color.Gray)
                                    Text("${df.format(line.debit)} د.ل", fontSize = 11.sp, color = EditorialIncomeText, fontWeight = FontWeight.Black)
                                } else {
                                    Text("دائن Cr (-)", fontSize = 10.sp, color = Color.Gray)
                                    Text("${df.format(line.credit)} د.ل", fontSize = 11.sp, color = EditorialExpenseText, fontWeight = FontWeight.Black)
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // Audit footer metadata log info
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(EditorialSurfaceVariant)
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text("مسار تدقيق الحسابات (Audit Metadata Log):", fontSize = 9.sp, color = Color.Gray, fontWeight = FontWeight.Bold)
                Text("تاريخ المعاملة المعتمد: ${entry.date} ${entry.createdAt}", fontSize = 10.sp)
                Text("مُعِد القيد والمرحل: ${entry.createdBy}", fontSize = 10.sp)
                Text("مستوى التوثيق الفرعي: ${entry.branch.nameAr}", fontSize = 10.sp)
            }

            // Print & download invoice actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { viewModel.showToast("تم توليد ملف PDF للمستند وجاري تحميله") },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("تحميل PDF", fontSize = 11.sp)
                }

                Button(
                    onClick = { viewModel.showToast("جاري إرسال سند القيد المالي للطابعة المتصلة بالفرع") },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = EditorialPrimary),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("طباعة المستند", fontSize = 11.sp)
                }
            }
        }
    }
}

// ==========================================
// SCREEN 5: COMPREHENSIVE CONFIGURATION SETTINGS
// ==========================================

@Composable
fun SettingsScreen(viewModel: FinancialViewModel) {
    var editCompanyName by remember { mutableStateOf(viewModel.companyName) }
    var editCurrency by remember { mutableStateOf(viewModel.baseCurrency) }
    var editTaxRate by remember { mutableStateOf(viewModel.defaultTaxRate.toString()) }
    var editCashLimit by remember { mutableStateOf(viewModel.cashLimitWarning.toString()) }
    var editFiscalYear by remember { mutableStateOf(viewModel.currentFiscalYearName) }

    val df = DecimalFormat("#,##0.00")

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Welcoming card
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = EditorialPrimaryContainer),
                border = BorderStroke(1.dp, EditorialPrimary.copy(alpha = 0.2f)),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(50.dp)
                            .clip(CircleShape)
                            .background(EditorialPrimary),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("⚙️", fontSize = 24.sp)
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "إعدادات النظام المالي الشامل",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Black,
                            color = EditorialOnBackground
                        )
                        Text(
                            text = "تعديل السياسات المحاسبية وتخصيص معالم الفروع والإعدادات العامة للمؤسسة ماليًا وإداريًا ومحاسبيًا.",
                            fontSize = 11.sp,
                            color = EditorialOnSurfaceVariant,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }

        // Section 1: General Company settings
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFFEFF6FF)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("🏢", fontSize = 14.sp)
                        }
                        Text(
                            text = "الهيكل الأساسي وبيانات الشركة والفرع",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = EditorialOnBackground
                        )
                    }

                    HorizontalDivider(color = Color(0xFFF1F5F9))

                    // Company Name
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("اسم الشركة / المؤسسة القانونية:", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                        OutlinedTextField(
                            value = editCompanyName,
                            onValueChange = { editCompanyName = it },
                            placeholder = { Text("أدخل اسم الشركة") },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth().testTag("config_company_name_field")
                        )
                    }

                    // Fiscal Year
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("السنة المالية الحالية المفتوحة:", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                        OutlinedTextField(
                            value = editFiscalYear,
                            onValueChange = { editFiscalYear = it },
                            placeholder = { Text("مثال: السنة المالية 2026") },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth().testTag("config_fiscal_year_field")
                        )
                    }

                    // Active branch notification
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFFFFFBEB), RoundedCornerShape(12.dp))
                            .border(1.dp, Color(0xFFFDE68A), RoundedCornerShape(12.dp))
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("⚠️", fontSize = 16.sp)
                        Column(modifier = Modifier.weight(1f)) {
                            Text("الفرع النشط حالياً:", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF92400E))
                            Text(
                                text = "أنت تتصفح وتدير عمليات \"${viewModel.currentBranch.nameAr}\" برمز \"${viewModel.currentBranch.code}\". المدير الإداري المسؤول هو \"${viewModel.currentBranch.managerAr}\" لدائرة أصناف \"${viewModel.currentBranch.inventoryCategory}\".",
                                fontSize = 10.sp,
                                color = Color(0xFFB45309)
                            )
                        }
                    }
                }
            }
        }

        // Section 2: Financial & Accounting parameters
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFFECFDF5)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("⚖️", fontSize = 14.sp)
                        }
                        Text(
                            text = "المعايير والفرضيات المحاسبية (IFRS)",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = EditorialOnBackground
                        )
                    }

                    HorizontalDivider(color = Color(0xFFF1F5F9))

                    // Base Currency
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("الرمز الرسمي للعملة الأساسية (الدرج المالي):", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                        OutlinedTextField(
                            value = editCurrency,
                            onValueChange = { editCurrency = it },
                            placeholder = { Text("مثال: د.ل") },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth().testTag("config_currency_field")
                        )
                    }

                    // Default Tax Rate
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("نسبة الضريبة المفروضة المعتمدة (%):", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                        OutlinedTextField(
                            value = editTaxRate,
                            onValueChange = { editTaxRate = it },
                            placeholder = { Text("مثال: 5.0") },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth().testTag("config_tax_rate_field")
                        )
                    }

                    // Cash Register Limit warning
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("حد النقدية الأقصى المحذر للخزينة (${viewModel.baseCurrency}):", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                        OutlinedTextField(
                            value = editCashLimit,
                            onValueChange = { editCashLimit = it },
                            placeholder = { Text("مثال: 50000.00") },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth().testTag("config_cash_limit_field")
                        )
                    }

                    // Toggle Automatic posting
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("الترحيل التلقائي للقيود والمستندات:", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = EditorialOnBackground)
                            Text("الترحيل الفوري بدون المرور بمرحلة قيد المسودة المؤقت لجرد الصندوق والذمم.", fontSize = 9.sp, color = Color.Gray)
                        }
                        Switch(
                            checked = viewModel.isAutoPostingEnabled,
                            onCheckedChange = { viewModel.isAutoPostingEnabled = it },
                            colors = SwitchDefaults.colors(checkedThumbColor = EditorialPrimary)
                        )
                    }
                }
            }
        }

        // Section 3: Safe systems & Operations actions
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color.White),
                border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFFFFF1F2)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("🛡️", fontSize = 14.sp)
                        }
                        Text(
                            text = "الأمان والصيانة والمظهر العام",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = EditorialOnBackground
                        )
                    }

                    HorizontalDivider(color = Color(0xFFF1F5F9))

                    // Dark mode toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("تفعيل المظهر الداكن الهادئ (Dark Mode):", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = EditorialOnBackground)
                            Text("يحول مظهر التطبيق إلى تباين مريح لأجهزة شؤون الخزينة وموظفي الاستقبال ليلاً.", fontSize = 9.sp, color = Color.Gray)
                        }
                        Switch(
                            checked = viewModel.isDarkModeEnabled,
                            onCheckedChange = { viewModel.isDarkModeEnabled = it },
                            colors = SwitchDefaults.colors(checkedThumbColor = EditorialPrimary)
                        )
                    }

                    HorizontalDivider(color = Color(0xFFF1F5F9))

                    // Actions triggers buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                viewModel.showToast("⚡ تم فرض مزامنة آمنة للبيانات والقيود بنجاح مع السيرفر السحابي")
                            },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("فرض المزامنة 🔄", fontSize = 11.sp)
                        }

                        OutlinedButton(
                            onClick = {
                                editCompanyName = "شركة اليمامة للحلول المالية والمقاولات"
                                editCurrency = "د.ل"
                                editTaxRate = "5.0"
                                editCashLimit = "50000.0"
                                editFiscalYear = "السنة المالية 2026"
                                viewModel.isAutoPostingEnabled = true
                                viewModel.isDarkModeEnabled = false
                                viewModel.showToast("🔄 تم استعادة القيم الافتراضية للنظام بنجاح!")
                            },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("استعادة الافتراضي ⚠️", fontSize = 11.sp)
                        }
                    }
                }
            }
        }

        // Apply and Save changes globally
        item {
            Button(
                onClick = {
                    if (editCompanyName.isBlank()) {
                        viewModel.showToast("الرجاء إدخال اسم شركة صحيح!")
                        return@Button
                    }
                    val taxVal = editTaxRate.toDoubleOrNull() ?: 5.0
                    val cashVal = editCashLimit.toDoubleOrNull() ?: 50000.0

                    viewModel.companyName = editCompanyName
                    viewModel.baseCurrency = editCurrency
                    viewModel.defaultTaxRate = taxVal
                    viewModel.cashLimitWarning = cashVal
                    viewModel.currentFiscalYearName = editFiscalYear

                    viewModel.showToast("✅ تم حفظ الإعدادات وتعميم السياسة المحاسبية بنجاح!")
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .testTag("apply_settings_button"),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = EditorialPrimary)
            ) {
                Text(
                    text = "حفظ وتثبيت تعديلات النظام المالي 💾",
                    color = Color.White,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 13.sp
                )
            }
        }

        item {
            Spacer(modifier = Modifier.height(60.dp))
        }
    }
}
