package dev.chungjungsoo.gptmobile.data.agent.tool

import android.content.Context
import androidx.room.Room

internal object GitHubEngineeringDatabaseFactory {
    @Volatile
    private var instance: GitHubEngineeringDatabase? = null

    fun open(context: Context): GitHubEngineeringDatabase =
        instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                GitHubEngineeringDatabase::class.java,
                "engineering-ledger.db",
            ).build().also { instance = it }
        }
}
