package ro.newsfilter.app.core.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import ro.newsfilter.app.data.local.AppDatabase
import ro.newsfilter.app.data.local.dao.NewsDao
import javax.inject.Singleton

/**
 * Modul Hilt pentru furnizarea bazei de date Room și a DAO-urilor.
 *
 * Decizie tehnică: Room funcționează ca cache LOCAL pe dispozitiv,
 * în paralel cu Cloud Firestore (baza de date REMOTE).
 * fallbackToDestructiveMigration() = în faza de dezvoltare, dacă
 * schimbăm schema, ștergem baza și o recreăm (la producție se face migrare).
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "newsfilter_db"
        )
            // Migrările explicite păstrează favoritele și voturile utilizatorului;
            // fallback-ul distructiv rămâne doar ca plasă de siguranță.
            .addMigrations(
                AppDatabase.MIGRATION_1_2,
                AppDatabase.MIGRATION_2_3,
                AppDatabase.MIGRATION_3_4,
                AppDatabase.MIGRATION_4_5,
                AppDatabase.MIGRATION_5_6,
                AppDatabase.MIGRATION_6_7,
                AppDatabase.MIGRATION_7_8
            )
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    @Singleton
    fun provideNewsDao(database: AppDatabase): NewsDao = database.newsDao()
}

