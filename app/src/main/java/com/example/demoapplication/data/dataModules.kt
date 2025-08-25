package com.example.demoapplication.data

import io.objectbox.annotation.Entity
import io.objectbox.annotation.HnswIndex
import io.objectbox.annotation.Id
import io.objectbox.annotation.Index

@Entity
/*data class FaceImageRecord(
    // primary-key of `FaceImageRecord`
    @Id var recordID: Long = 0,

    // personId is derived from `PersonRecord`
    @Index var personID: Long = 0,
    var personName: String = "",

    // the FaceNet-512 model provides a 512-dimensional embedding
    // the FaceNet model provides a 128-dimensional embedding
    @HnswIndex(dimensions = 512) var faceEmbedding: FloatArray = floatArrayOf(),

    var gender: String? = null,
    var ageGroup: String? = null,
    @Index var expression: String? = null
)*/

data class FaceImageRecord(
    @Id var id: Long = 0,
    @Index var personID: Long = 0,
    var personName: String = "",
    @HnswIndex(dimensions = 512) var faceEmbedding: FloatArray = floatArrayOf(),
    var gender: String? = null,
    var ageGroup: String? = null,
    var expression: String? = null,
    var createdAt: Long = System.currentTimeMillis() // NEW field
)

@Entity
data class PersonRecord(
    // primary-key
    @Id var personID: Long = 0,
    var personName: String = "",

    // number of images selected by the user
    // under the name of the person
    var numImages: Long = 0,

    // time when the record was added
    var addTime: Long = 0
)

data class RecognitionMetrics(
    val timeFaceDetection: Long,
    val timeVectorSearch: Long,
    val timeFaceEmbedding: Long,
    val timeFaceSpoofDetection: Long,
    val timeExpressionDetection: Long = 0,
    val timeAgeGenderDetection: Long
)