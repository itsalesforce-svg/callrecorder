package com.example.callrecorder.data.repository

import com.example.callrecorder.data.local.CallRecordDao
import com.example.callrecorder.data.local.CallRecordEntity
import kotlinx.coroutines.flow.Flow
import java.io.File

class CallRecordRepository(private val dao: CallRecordDao) {

    val allRecords: Flow<List<CallRecordEntity>> = dao.getAllRecords()

    suspend fun getUnsyncedRecords(): List<CallRecordEntity> {
        return dao.getUnsyncedRecords()
    }

    suspend fun insertRecord(record: CallRecordEntity): Long {
        return dao.insertRecord(record)
    }

    suspend fun updateSyncStatus(id: Long, isSynced: Boolean) {
        dao.updateSyncStatus(id, isSynced)
    }

    suspend fun deleteRecord(record: CallRecordEntity) {
        dao.deleteRecord(record)
        // Also remove audio file from local disk storage
        try {
            val file = File(record.filePath)
            if (file.exists()) {
                file.delete()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
