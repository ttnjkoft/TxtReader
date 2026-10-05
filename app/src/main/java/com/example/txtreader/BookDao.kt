package com.example.txtreader

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface BookDao {
    @Query("SELECT * FROM books ORDER BY lastOpen DESC, addedAt DESC")
    fun all(): List<Book>

    @Query("SELECT * FROM books WHERE uri = :uri LIMIT 1")
    fun byUri(uri: String): Book?

    /** 同名書：SAF Uri 會變（重開機／媒體庫重掃），檔名才是本體。 */
    @Query("SELECT * FROM books WHERE name = :name LIMIT 1")
    fun byName(name: String): Book?

    @Upsert
    fun upsert(b: Book)

    @Query("UPDATE books SET lastLine = :line, lastOffset = :off, pct = :pct, chapter = :chapter, lastOpen = :t WHERE uri = :uri")
    fun saveProgress(uri: String, line: Int, off: Int, pct: Int, chapter: String, t: Long)

    @Query("DELETE FROM books WHERE uri = :uri")
    fun delete(uri: String)
}
