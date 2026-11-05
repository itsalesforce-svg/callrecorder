package com.example.callrecorder.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface CallRecordDao {

    @Query("SELECT * FROM call_records ORDER BY timestamp DESC")
    fun getAllRecords(): Flow<List<CallRecordEntity>>

    @Query("SELECT * FROM call_records WHERE isSynced = 0 ORDER BY timestamp ASC")
    suspend fun getUnsyncedRecords(): List<CallRecordEntity>

    @Query("SELECT * FROM call_records WHERE id = :id")
    suspend fun getRecordById(id: Long): CallRecordEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRecord(record: CallRecordEntity): Long

    @Update
    suspend fun updateRecord(record: CallRecordEntity)

    @Delete
    suspend fun deleteRecord(record: CallRecordEntity)

    @Query("UPDATE call_records SET isSynced = :synced WHERE id = :id")
    suspend fun updateSyncStatus(id: Long, synced: Boolean)

    @Query("DELETE FROM call_records WHERE id = :id")
    suspend fun deleteRecordById(id: Long)
}
