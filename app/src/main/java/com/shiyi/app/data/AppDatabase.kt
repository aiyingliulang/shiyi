package com.shiyi.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * 数据库版本与迁移：schema 导出在 app/schemas/ 下。
 * 以后改表结构时：version + 1，并在 MIGRATIONS 中加入对应 Migration，
 * 绝不使用 fallbackToDestructiveMigration，以免丢失个人数据。
 */
@Database(
    entities = [
        RecordEntity::class, FoodItemEntity::class, RecordFoodLinkEntity::class, RecordImageEntity::class,
        SnackEntity::class, SnackImageEntity::class,
    ],
    version = 2,
    exportSchema = true,
    // v2：新增零食表（只加表，Room 自动生成迁移，原有数据不变）
    autoMigrations = [androidx.room.AutoMigration(from = 1, to = 2)],
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun records(): RecordDao
    abstract fun foods(): FoodDao
    abstract fun links(): LinkDao
    abstract fun images(): ImageDao
    abstract fun snacks(): SnackDao

    companion object {
        const val NAME = "shiyi.db"
        val MIGRATIONS: Array<androidx.room.migration.Migration> = emptyArray()

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, NAME)
                .addMigrations(*MIGRATIONS)
                .build()
    }
}
