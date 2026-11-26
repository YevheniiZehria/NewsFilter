package ro.newsfilter.app.core.di

import com.squareup.moshi.Moshi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import ro.newsfilter.app.BuildConfig
import ro.newsfilter.app.core.auth.SessionManager
import ro.newsfilter.app.core.network.ApiKeyInterceptor
import ro.newsfilter.app.core.network.AuthInterceptor
import ro.newsfilter.app.data.remote.NewsApiService
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/**
 * Modul Hilt pentru furnizarea dependențelor de rețea.
 *
 * Decizie tehnică: @InstallIn(SingletonComponent) = instanță unică per aplicație.
 * OkHttp include:
 *   - ApiKeyInterceptor: adaugă cheia API a aplicației (X-API-Key)
 *   - AuthInterceptor: adaugă token-ul de sesiune (Bearer) și tratează 401
 *   - HttpLoggingInterceptor: loghează cererile (doar în debug, cu secretele mascate)
 *   - Timeout-uri: 15s conectare, 60s citire (workflow-urile n8n pot fi lente)
 *
 * Moshi e preferat față de Gson pentru:
 *   - Generare de cod la compilare prin KSP (@JsonClass(generateAdapter = true)),
 *     fără reflecție la runtime — de aceea nu înregistrăm KotlinJsonAdapterFactory
 *     și nu depindem de kotlin-reflect
 *   - Null-safety nativă (aruncă erori la câmpuri null neasumate)
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideMoshi(): Moshi = Moshi.Builder().build()

    @Provides
    @Singleton
    fun provideOkHttpClient(sessionManager: SessionManager): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .addInterceptor(ApiKeyInterceptor())
            .addInterceptor(AuthInterceptor(sessionManager))
            // Conectarea eșuează repede dacă n8n e oprit; citirea tolerează workflow-uri
            // lente (n8n „rece”, „Aprofundează” cu Gemini ~10-25s).
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)

        // Logging doar în debug, nu în release (securitate + performanță).
        // Header-ele cu secrete sunt mascate și în debug.
        if (BuildConfig.DEBUG) {
            val loggingInterceptor = HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BODY
                redactHeader("Authorization")
                redactHeader("X-API-Key")
            }
            builder.addInterceptor(loggingInterceptor)
        }

        return builder.build()
    }

    @Provides
    @Singleton
    fun provideRetrofit(okHttpClient: OkHttpClient, moshi: Moshi): Retrofit =
        Retrofit.Builder()
            .baseUrl(BuildConfig.N8N_BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()

    @Provides
    @Singleton
    fun provideNewsApiService(retrofit: Retrofit): NewsApiService =
        retrofit.create(NewsApiService::class.java)
}

