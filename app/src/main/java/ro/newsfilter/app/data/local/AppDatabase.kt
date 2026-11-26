package ro.newsfilter.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import ro.newsfilter.app.data.local.dao.NewsDao
import ro.newsfilter.app.data.local.entity.NewsEntity

/**
 * Baza de date Room a aplicației.
 *
 * Decizie tehnică: Folosim o singură tabelă „news" care funcționează
 * atât ca cache offline, cât și pentru stocarea favoritelor și a
 * stării de citire.
 *  - v1 = schema inițială;
 *  - v2 = coloana `user_vote` (votul Like / Dislike, păstrat la redeschiderea articolului);
 *  - v3 = textul integral și rezumatul AI;
 *  - v4 = Smart Digest (idei cheie + etichete) și analiza „Aprofundează”;
 *  - v5 = Market Pulse (perechea valutară influențată, direcția și nota AI).
 * exportSchema = true păstrează istoricul schemelor în app/schemas.
 */
@Database(
    entities = [NewsEntity::class],
    version = 5,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun newsDao(): NewsDao

    companion object {
        /** Adaugă coloana pentru vot fără a pierde favoritele / articolele citite. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE news ADD COLUMN user_vote INTEGER")
            }
        }

        /** Adaugă coloanele pentru textul integral și rezumatul AI. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE news ADD COLUMN full_content TEXT")
                db.execSQL("ALTER TABLE news ADD COLUMN ai_summary TEXT")
            }
        }

        /** Adaugă coloanele Smart Digest și cache-ul pentru „Aprofundează subiectul”. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE news ADD COLUMN ai_bullets TEXT")
                db.execSQL("ALTER TABLE news ADD COLUMN ai_tags TEXT")
                db.execSQL("ALTER TABLE news ADD COLUMN deep_dive TEXT")
            }
        }

        /** Adaugă câmpurile Market Pulse (impactul AI al știrii asupra unei perechi valutare). */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE news ADD COLUMN affected_currency TEXT")
                db.execSQL("ALTER TABLE news ADD COLUMN market_sentiment TEXT")
                db.execSQL("ALTER TABLE news ADD COLUMN market_impact_note TEXT")
            }
        }
    }
}

