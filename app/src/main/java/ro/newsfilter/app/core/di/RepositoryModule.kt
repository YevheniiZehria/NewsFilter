package ro.newsfilter.app.core.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import ro.newsfilter.app.data.repository.AuthRepositoryImpl
import ro.newsfilter.app.data.repository.MarketRepositoryImpl
import ro.newsfilter.app.data.repository.NewsRepositoryImpl
import ro.newsfilter.app.domain.repository.AuthRepository
import ro.newsfilter.app.domain.repository.MarketRepository
import ro.newsfilter.app.domain.repository.NewsRepository
import javax.inject.Singleton

/**
 * Modul Hilt care leagă interfața NewsRepository de implementarea concretă.
 *
 * Decizie tehnică: @Binds este mai eficient decât @Provides pentru
 * binding-uri simple de interfață → implementare (generează mai puțin cod).
 * Orice clasă care depinde de NewsRepository va primi automat
 * NewsRepositoryImpl prin injecție.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindNewsRepository(
        impl: NewsRepositoryImpl
    ): NewsRepository

    @Binds
    @Singleton
    abstract fun bindAuthRepository(
        impl: AuthRepositoryImpl
    ): AuthRepository

    @Binds
    @Singleton
    abstract fun bindMarketRepository(
        impl: MarketRepositoryImpl
    ): MarketRepository
}
