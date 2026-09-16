package com.elfred434.transciber.di

import android.content.Context
import androidx.room.Room
import com.elfred434.transciber.data.HistoryDao
import com.elfred434.transciber.data.TransciberDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): TransciberDatabase =
        Room.databaseBuilder(context, TransciberDatabase::class.java, "transciber.db").build()

    @Provides
    fun provideHistoryDao(database: TransciberDatabase): HistoryDao = database.historyDao()

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()
}
