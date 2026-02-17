package com.fushilaofang.texasholdemchipsim.data

import android.content.Context
import com.fushilaofang.texasholdemchipsim.model.ChipTransaction
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class TransactionRepository(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }
    private val filename = "chip_transactions.json"

    fun load(): List<ChipTransaction> {
        return runCatching {
            val text = context.openFileInput(filename).bufferedReader().use { it.readText() }
            if (text.isBlank()) emptyList() else json.decodeFromString<List<ChipTransaction>>(text)
        }.getOrDefault(emptyList())
    }

    fun save(transactions: List<ChipTransaction>) {
        val text = json.encodeToString(transactions)
        context.openFileOutput(filename, Context.MODE_PRIVATE).bufferedWriter().use { it.write(text) }
    }
}
