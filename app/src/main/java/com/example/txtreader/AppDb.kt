package com.example.txtreader

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [Book::class], version = 1, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun books(): BookDao
}

/** 單例 holder：用 applicationContext，不漏 Activity。 */
object Db {
    @Volatile
    private var inst: AppDb? = null

    fun get(ctx: Context): AppDb = inst ?: synchronized(this) {
        inst ?: Room.databaseBuilder(ctx.applicationContext, AppDb::class.java, "txtreader.db")
            .build()
            .also { inst = it }
    }
}
