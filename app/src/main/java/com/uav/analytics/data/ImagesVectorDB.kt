package com.uav.analytics.data

import android.content.Context
import android.util.Log
import com.uav.analytics.RemoteConfigHelper
import io.objectbox.kotlin.boxFor
import io.objectbox.kotlin.query
import io.objectbox.query.QueryBuilder
import javax.inject.Singleton

@Singleton
class ImagesVectorDB(context: Context) {

    private val imagesBox = ObjectBoxStore.store.boxFor<FaceImageRecord>()

    fun addFaceImageRecord(record: FaceImageRecord) {
        record.createdAt = System.currentTimeMillis()
        imagesBox.put(record)
    }

    fun removeDataByPersonId(personId: Long): Boolean {
        return try {
            // Find all records with the given personId
            val recordsToRemove = imagesBox.query {
                equal(FaceImageRecord_.personID, personId)
            }.findIds()

            if (recordsToRemove.isNotEmpty()) {
                // Convert LongArray to List<Long>
                imagesBox.removeByIds(recordsToRemove.toList())
                Log.d("ImagesVectorDB", "✅ Removed ${recordsToRemove.size} records for personId: $personId")
                true
            } else {
                Log.d("ImagesVectorDB", "ℹ️ No records found for personId: $personId")
                false
            }
        } catch (e: Exception) {
            Log.e("ImagesVectorDB", "❌ Error removing data for personId $personId: ${e.message}")
            false
        }
    }

    fun getNearestEmbeddingPersonName(embedding: FloatArray): FaceImageRecord? {
        return imagesBox.query(FaceImageRecord_.faceEmbedding.nearestNeighbors(embedding, 10))
            .build()
            .findWithScores()
            .map { it.get() }
            .firstOrNull()
    }

    fun removeExpiredRecords() {
        val expirationTimeMillis = try {
            RemoteConfigHelper.getExpirationHours()
        } catch (e: Exception) {
            Log.e("ImagesVectorDB", "Error getting remote config, using default: ${e.message}")
            1 * 60 * 1000 // Fallback to 1 minute  in ms
        }

        val expirationThreshold = System.currentTimeMillis() - expirationTimeMillis
        val expiredIds = imagesBox.query(FaceImageRecord_.createdAt.less(expirationThreshold))
            .build()
            .findIds().toList()

        if (expiredIds.isNotEmpty()) {
            imagesBox.removeByIds(expiredIds)
            Log.d("ImagesVectorDB", "Removed ${expiredIds.size} expired records (threshold: ${expirationTimeMillis/1000/60} minutes)")
        }
    }

    fun removeFaceRecordsWithPersonID(personID: Long) {
        val idsToRemove = imagesBox.query(FaceImageRecord_.personID.equal(personID))
            .build()
            .findIds()
            .toList()

        imagesBox.removeByIds(idsToRemove)
    }

    fun getCount(): Long {
        return imagesBox.count()
    }

    fun getCountByGender(gender: String): Long {
        return imagesBox.query(FaceImageRecord_.gender.equal(gender))
            .build()
            .count()
    }

    fun getCountByAgeGroup(ageGroup: String): Long {
        return imagesBox.query(FaceImageRecord_.ageGroup.equal(ageGroup))
            .build()
            .count()
    }

    fun getCountByExpression(expression: String): Long {
        return try {
            imagesBox.query(FaceImageRecord_.expression.equal(expression).and(FaceImageRecord_.expression.notNull()))
                .build()
                .count()
        } catch (e: Exception) {
            Log.e("ImagesVectorDB", "Error querying expression count: ${e.message}")
            0L
        }
    }

    fun clearAll() {
        return imagesBox.removeAll()
    }

    fun getCreatedTimeByPerson(personId: Long): Long {
        val record = imagesBox.query(FaceImageRecord_.personID.equal(personId))
            .build()
            .findFirst()
        return record?.createdAt ?: 0L
    }

    fun getIsOldPersonByPerson(personId: Long): Boolean {
        val record = imagesBox.query(FaceImageRecord_.personID.equal(personId))
            .build()
            .findFirst()
        return record?.isOldPerson ?: false
    }

    fun getLatestFaceImageRecord(personId: Long): FaceImageRecord? {
        return imagesBox.query(FaceImageRecord_.personID.equal(personId))
            .order(FaceImageRecord_.createdAt, QueryBuilder.DESCENDING)
            .build()
            .findFirst()
    }
}