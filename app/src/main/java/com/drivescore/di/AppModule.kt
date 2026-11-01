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
        Room.databaseBuilder(context, DriveDatabase::class.java, "drive.db")
            // 아직 배포 전이라 스키마가 바뀌면 지운다. 첫 배포 후에는 마이그레이션을 작성할 것.
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun dao(db: DriveDatabase): DriveDao = db.dao()

    @Provides @Singleton
    fun voiceOutput(@ApplicationContext context: Context): VoiceOutput =
        FallbackVoiceOutput(PleosVoiceOutput(context), AndroidVoiceOutput(context))

    @Provides @Singleton
    fun voiceInput(@ApplicationContext context: Context): VoiceInput =
        FallbackVoiceInput(PleosVoiceInput(context), AndroidVoiceInput(context))
}
