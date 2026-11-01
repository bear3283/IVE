package com.drivescore.di

import android.content.Context
import androidx.room.Room
import com.drivescore.data.db.DriveDao
import com.drivescore.data.db.DriveDatabase
import com.drivescore.data.voice.*
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides @Singleton
    fun database(@ApplicationContext context: Context): DriveDatabase =
        Room.databaseBuilder(context, DriveDatabase::class.java, "drive.db").build()

    @Provides
    fun dao(db: DriveDatabase): DriveDao = db.dao()

    @Provides @Singleton
    fun voiceOutput(@ApplicationContext context: Context): VoiceOutput =
        FallbackVoiceOutput(PleosVoiceOutput(context), AndroidVoiceOutput(context))

    @Provides @Singleton
    fun voiceInput(@ApplicationContext context: Context): VoiceInput =
        FallbackVoiceInput(PleosVoiceInput(context), AndroidVoiceInput(context))
}
