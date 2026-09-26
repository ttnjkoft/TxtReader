package com.example.txtreader

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 書架上的一本書：SAF Uri 主鍵 + 上次看到的位置。
 * 單表搞定書架和歷史，不必分兩張表。
 */
@Entity(tableName = "books")
data class Book(
    @PrimaryKey val uri: String,
    val name: String,
    val addedAt: Long = System.currentTimeMillis(),
    val lastLine: Int = 0,
    val lastOffset: Int = 0,
    val pct: Int = 0,
    val chapter: String = "",
    val lastOpen: Long = 0L
)
