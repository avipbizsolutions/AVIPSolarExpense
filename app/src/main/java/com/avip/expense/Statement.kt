package com.avip.expense

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.os.Build
import android.provider.MediaStore
import android.text.TextPaint
import android.text.TextUtils
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One person's statement for a period: their claims and the advances given to them,
 * as a PDF (to read / print) or a CSV file (opens in Excel and Google Sheets).
 * Made on the phone from what the Team records screen already has.
 */
object Statement {

    class Data(
        val person: String, val from: String, val to: String,
        val claims: List<Claim>, val advances: List<Advance>,
        /** Advance with the person right now (not for the period), when known. */
        val advanceNow: Double?,
    )

    private fun paidBy(c: Claim) = when (c.paidBy) { "Advance" -> "From advance"; "Company" -> "Company"; else -> "Own money" }
    private fun status(c: Claim) = when (c.status) { Status.PENDING -> "Pending"; Status.REIMBURSED -> "Paid"; else -> c.status }

    private fun file(c: Context, d: Data, ext: String): File {
        val dir = File(c.cacheDir, "shared").apply { mkdirs() }
        val who = d.person.replace(Regex("[^A-Za-z0-9]+"), "_").trim('_').ifBlank { "person" }
        return File(dir, "AVIP_Expense_Statement_${who}_${d.from}_to_${d.to}.$ext")
    }

    /** Totals shown at the top of both files. */
    private fun summary(d: Data): List<Pair<String, String>> {
        val live = d.claims.filter { it.status != Status.REJECTED }
        fun sum(st: String) = d.claims.filter { it.status == st }.sumOf { it.amount }
        return buildList {
            add("Claims (not rejected)" to "${live.size} · " + Fmt.rupees(live.sumOf { it.amount }))
            add("Pending approval" to Fmt.rupees(sum(Status.PENDING)))
            add("Approved" to Fmt.rupees(sum(Status.APPROVED)))
            add("Paid" to Fmt.rupees(sum(Status.REIMBURSED)))
            add("Rejected" to Fmt.rupees(sum(Status.REJECTED)))
            add("Spent from advance" to Fmt.rupees(live.filter { it.paidBy == "Advance" }.sumOf { it.amount }))
            add("Own money (to be paid back)" to Fmt.rupees(live.filter { it.paidBy == "Self" }.sumOf { it.amount }))
            add("Advances given in this period" to "${d.advances.size} · " + Fmt.rupees(d.advances.sumOf { it.amount }))
            if (d.advanceNow != null) add("Advance with them today" to Fmt.rupees(d.advanceNow))
        }
    }

    // ------------------------------------------------------------------ CSV (Excel)

    private fun q(v: Any?): String {
        var s = (v ?: "").toString().replace("\r", " ").replace("\n", " ")
        // a cell must never start a formula in Excel
        if (s.isNotEmpty() && s[0] in "=+-@") s = "'$s"
        return "\"" + s.replace("\"", "\"\"") + "\""
    }

    private fun amt(v: Double) = if (v % 1.0 == 0.0) v.toLong().toString() else String.format(Locale.US, "%.2f", v)

    fun csv(c: Context, d: Data): File {
        val sb = StringBuilder()
        fun row(vararg v: Any?) { sb.append(v.joinToString(",") { if (it is Number) it.toString() else q(it) }).append("\r\n") }
        row("AVIP Solar - Expense statement")
        row("Person", d.person)
        row("Period", Fmt.date(d.from) + " to " + Fmt.date(d.to))
        row("Made on", SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.ENGLISH).format(Date()))
        row()
        summary(d).forEach { row(it.first, it.second.replace("₹", "Rs ")) }
        row()
        row("CLAIMS")
        row("Date", "Claim ID", "Category", "Purpose", "Site", "Location", "KM", "Paid by", "Payment mode", "Status", "Amount (Rs)", "Approved by", "Paid on", "Paid via", "Reference", "Remarks")
        d.claims.forEach { x ->
            sb.append(listOf(q(x.date), q(x.id), q(x.category), q(x.description), q(x.site), q(x.district), if (x.km > 0) Fmt.km(x.km) else "\"\"",
                q(paidBy(x)), q(x.paymentMode), q(status(x)), amt(x.amount), q(x.approvedBy), q(x.reimbursedOn), q(x.reimbursedVia), q(x.paymentRef), q(x.remarks)).joinToString(",")).append("\r\n")
        }
        row("", "", "", "", "", "", "", "", "", "Total (not rejected)", amt(d.claims.filter { it.status != Status.REJECTED }.sumOf { it.amount }))
        row()
        row("ADVANCES GIVEN")
        row("Date", "Advance ID", "Paid via", "Reference", "Purpose", "Given by", "Amount (Rs)")
        d.advances.forEach { a -> sb.append(listOf(q(a.date), q(a.id), q(a.via), q(a.ref), q(a.purpose), q(a.givenBy), amt(a.amount)).joinToString(",")).append("\r\n") }
        row("", "", "", "", "", "Total", amt(d.advances.sumOf { it.amount }))
        val f = file(c, d, "csv")
        // the BOM makes Excel read Hindi text correctly
        f.writeBytes(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + sb.toString().toByteArray(Charsets.UTF_8))
        return f
    }

    // ------------------------------------------------------------------ PDF (A4)

    private const val W = 595
    private const val H = 842
    private const val M = 36f

    fun pdf(c: Context, d: Data): File {
        val doc = PdfDocument()
        val normal = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 9f; color = 0xFF1F1A2B.toInt() }
        val bold = TextPaint(normal).apply { typeface = Typeface.DEFAULT_BOLD }
        val muted = TextPaint(normal).apply { color = 0xFF6B6478.toInt() }
        val title = TextPaint(bold).apply { textSize = 16f; color = 0xFF4A2A85.toInt() }
        val head = TextPaint(bold).apply { textSize = 11.5f }
        val line = Paint().apply { color = 0xFFD9D2E6.toInt(); strokeWidth = 0.7f }
        val band = Paint().apply { color = 0xFFF1ECF8.toInt() }

        var pageNo = 0
        lateinit var page: PdfDocument.Page
        lateinit var cv: Canvas
        var y = 0f
        fun newPage() {
            if (pageNo > 0) doc.finishPage(page)
            pageNo++
            page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, pageNo).create())
            cv = page.canvas
            cv.drawText("AVIP Solar · Expense statement · ${d.person}", M, H - 20f, muted)
            val pg = "Page $pageNo"
            cv.drawText(pg, W - M - muted.measureText(pg), H - 20f, muted)
            y = M + 10f
        }
        fun need(h: Float) { if (y + h > H - 40f) newPage() }
        fun fit(s: String, p: TextPaint, w: Float) = TextUtils.ellipsize(s.replace("\n", " "), p, w - 6f, TextUtils.TruncateAt.END).toString()

        /** A table: column titles, widths (sum = page width), which columns are right-aligned. */
        fun table(cols: List<String>, widths: List<Float>, right: Set<Int>, rows: List<List<String>>, total: List<String>?) {
            fun draw(cells: List<String>, p: TextPaint) {
                var x = M
                cells.forEachIndexed { i, s ->
                    val t = fit(s, p, widths[i])
                    if (i in right) cv.drawText(t, x + widths[i] - 3f - p.measureText(t), y, p) else cv.drawText(t, x + 3f, y, p)
                    x += widths[i]
                }
            }
            fun header() {
                need(34f)
                cv.drawRect(M, y - 11f, W - M, y + 5f, band)
                draw(cols, bold)
                y += 17f
            }
            header()
            rows.forEach { r ->
                if (y + 15f > H - 40f) { newPage(); header() }
                draw(r, normal)
                cv.drawLine(M, y + 4.5f, W - M, y + 4.5f, line)
                y += 15f
            }
            if (total != null) { need(18f); y += 2f; draw(total, bold); y += 16f }
        }

        newPage()
        cv.drawText("AVIP Solar", M, y + 6f, title); y += 24f
        cv.drawText("Expense statement", M, y, head); y += 18f
        cv.drawText(d.person, M, y, TextPaint(bold).apply { textSize = 13f }); y += 15f
        cv.drawText("Period: " + Fmt.date(d.from) + " to " + Fmt.date(d.to), M, y, normal); y += 13f
        cv.drawText("Made on " + SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.ENGLISH).format(Date()), M, y, muted); y += 20f

        // ---- summary
        summary(d).forEach { (k, v) ->
            cv.drawText(k, M + 3f, y, normal)
            cv.drawText(v, M + 330f - bold.measureText(v), y, bold)
            cv.drawLine(M, y + 4.5f, M + 333f, y + 4.5f, line)
            y += 15f
        }
        y += 14f

        // ---- claims
        need(60f)
        cv.drawText("Claims (${d.claims.size})", M, y, head); y += 18f
        if (d.claims.isEmpty()) { cv.drawText("No claims in this period.", M + 3f, y, muted); y += 18f }
        else table(
            listOf("Date", "Category", "Purpose", "Paid by", "Status", "Amount"),
            listOf(58f, 92f, 178f, 66f, 59f, 70f), setOf(5),
            d.claims.map { x ->
                listOf(Fmt.date(x.date), x.category, x.description + (if (x.site.isNotBlank()) " · " + x.site else "") + (if (x.km > 0) " · " + Fmt.km(x.km) + " km" else ""),
                    paidBy(x), status(x), Fmt.rupees(x.amount))
            },
            listOf("", "", "", "", "Total", Fmt.rupees(d.claims.filter { it.status != Status.REJECTED }.sumOf { it.amount })),
        )
        if (d.claims.any { it.status == Status.REJECTED }) { need(14f); cv.drawText("The total leaves out rejected claims.", M + 3f, y - 4f, muted); y += 10f }
        y += 12f

        // ---- advances
        need(60f)
        cv.drawText("Advances given (${d.advances.size})", M, y, head); y += 18f
        if (d.advances.isEmpty()) { cv.drawText("No advances in this period.", M + 3f, y, muted); y += 18f }
        else table(
            listOf("Date", "Paid via", "Reference", "Purpose", "Given by", "Amount"),
            listOf(58f, 62f, 85f, 163f, 85f, 70f), setOf(5),
            d.advances.map { a -> listOf(Fmt.date(a.date), a.via, a.ref, a.purpose, a.givenBy, Fmt.rupees(a.amount)) },
            listOf("", "", "", "", "Total", Fmt.rupees(d.advances.sumOf { it.amount })),
        )

        doc.finishPage(page)
        val f = file(c, d, "pdf")
        f.outputStream().use { doc.writeTo(it) }
        doc.close()
        return f
    }

    // ------------------------------------------------------------------ save / open / share

    /** Copies the file into the phone's Downloads folder (Android 10 and newer). True when saved. */
    private fun toDownloads(c: Context, f: File, mime: String): Boolean {
        if (Build.VERSION.SDK_INT < 29) return false
        return try {
            val cr = c.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, f.name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/AVIP Expense")
            }
            val uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
            cr.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } } ?: return false
            true
        } catch (_: Exception) { false }
    }

    /** Saves to Downloads, then offers to open the file or share it (WhatsApp, email, Drive). */
    fun deliver(a: Activity, f: File, mime: String) {
        val saved = toDownloads(a, f, mime)
        val uri = FileProvider.getUriForFile(a, a.packageName + ".files", f)
        fun send(view: Boolean) {
            val i = if (view) Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
                else Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).putExtra(Intent.EXTRA_SUBJECT, f.nameWithoutExtension.replace('_', ' '))
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            try {
                a.startActivity(Intent.createChooser(i, if (view) tr("किससे खोलें", "Open with") else tr("भेजें", "Share")).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            } catch (_: Exception) {
                a.toast(tr("इसे खोलने के लिए कोई app नहीं मिला", "No app found to open this"))
            }
        }
        AlertDialog.Builder(a)
            .setTitle(tr("Statement तैयार है", "Statement ready"))
            .setMessage((if (saved) tr("Downloads › AVIP Expense में save हो गया:\n", "Saved in Downloads › AVIP Expense:\n") else "") + f.name)
            .setPositiveButton(tr("खोलें", "Open")) { _, _ -> send(true) }
            .setNeutralButton(tr("भेजें", "Share")) { _, _ -> send(false) }
            .setNegativeButton(tr("बंद करें", "Close"), null)
            .show()
    }
}
