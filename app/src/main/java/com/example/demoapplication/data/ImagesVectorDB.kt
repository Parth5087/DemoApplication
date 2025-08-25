package com.example.demoapplication.data

import android.content.Context
import android.util.Log
import io.objectbox.kotlin.boxFor
import javax.inject.Singleton

@Singleton
class ImagesVectorDB(context: Context) {

//    private val store = MyObjectBox.builder()
//        .androidContext(context)
//        .build()

    private val imagesBox = ObjectBoxStore.store.boxFor<FaceImageRecord>()

    fun addFaceImageRecord(record: FaceImageRecord) {
        record.createdAt = System.currentTimeMillis()
        imagesBox.put(record)
    }

    fun getNearestEmbeddingPersonName(embedding: FloatArray): FaceImageRecord? {
        return imagesBox.query(FaceImageRecord_.faceEmbedding.nearestNeighbors(embedding, 10))
            .build()
            .findWithScores()
            .map { it.get() }
            .firstOrNull()
    }

    fun removeExpiredRecords() {
        val oneHourAgo = System.currentTimeMillis() - (60 * 60 * 1000) // 1 hour in ms
        val expiredIds = imagesBox.query(FaceImageRecord_.createdAt.less(oneHourAgo))
            .build()
            .findIds().toList()

        if (expiredIds.isNotEmpty()) {
            imagesBox.removeByIds(expiredIds)
            Log.d("ImagesVectorDB", "Removed ${expiredIds.size} expired records")
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
}