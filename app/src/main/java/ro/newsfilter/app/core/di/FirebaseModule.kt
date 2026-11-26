package ro.newsfilter.app.core.di

import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.firestore
import com.google.firebase.firestore.firestoreSettings
import com.google.firebase.firestore.memoryCacheSettings
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Modul Hilt pentru Firebase (Authentication + Cloud Firestore).
 *
 * Configurația proiectului (ID, chei) vine din `app/google-services.json`, procesat la build de
 * pluginul `com.google.gms.google-services` (vezi FIREBASE_SETUP.md).
 *
 * Decizie tehnică: Firestore folosește doar cache în memorie. Cache-ul persistent al aplicației
 * este Room (sursa unică de adevăr pentru UI); un al doilea cache pe disc ar dubla datele.
 */
@Module
@InstallIn(SingletonComponent::class)
object FirebaseModule {

    @Provides
    @Singleton
    fun provideFirebaseAuth(): FirebaseAuth = Firebase.auth

    @Provides
    @Singleton
    fun provideFirestore(): FirebaseFirestore = Firebase.firestore.apply {
        firestoreSettings = firestoreSettings {
            setLocalCacheSettings(memoryCacheSettings {})
        }
    }
}
