package com.example.demoapplication

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.demoapplication.data.ImagesVectorDB
import com.example.demoapplication.data.PersonDB
import com.example.demoapplication.data.RecognitionMetrics
import com.example.demoapplication.domain.ImageVectorUseCase
import com.example.demoapplication.domain.PersonUseCase
import com.example.demoapplication.domain.embeddings.FaceNet
import com.example.demoapplication.domain.faceDection.FaceSpoofDetector
import com.example.demoapplication.domain.faceDection.MediapipeFaceDetector

class MainActivityViewModel(
    private val personUseCase: PersonUseCase,
    val imageVectorUseCase: ImageVectorUseCase
) : ViewModel() {

    private val _faceDetectionMetricsState = MutableLiveData<RecognitionMetrics?>()
    val faceDetectionMetricsState: LiveData<RecognitionMetrics?> = _faceDetectionMetricsState

    private val _faceCountsState = MutableLiveData<FaceCounts>()
    val faceCountsState: LiveData<FaceCounts> = _faceCountsState

    private val _expressionCountsState = MutableLiveData<ImageVectorUseCase.ExpressionCounts>()
    val expressionCountsState: LiveData<ImageVectorUseCase.ExpressionCounts> = _expressionCountsState

    private val _genderCountsState = MutableLiveData<ImageVectorUseCase.GenderCounts>()
    val genderCountsState: LiveData<ImageVectorUseCase.GenderCounts> = _genderCountsState

    private val _ageGroupCountsState = MutableLiveData<ImageVectorUseCase.AgeGroupCounts>()
    val ageGroupCountsState: LiveData<ImageVectorUseCase.AgeGroupCounts> = _ageGroupCountsState

    private val _storedGenderCountsState = MutableLiveData<ImageVectorUseCase.GenderCounts>()
    val storedGenderCountsState: LiveData<ImageVectorUseCase.GenderCounts> = _storedGenderCountsState

    private val _storedAgeGroupCountsState = MutableLiveData<ImageVectorUseCase.AgeGroupCounts>()
    val storedAgeGroupCountsState: LiveData<ImageVectorUseCase.AgeGroupCounts> = _storedAgeGroupCountsState

    private val _storedExpressionCountsState = MutableLiveData<ImageVectorUseCase.ExpressionCounts>()
    val storedExpressionCountsState: LiveData<ImageVectorUseCase.ExpressionCounts> = _storedExpressionCountsState

    private val detectedFaceNames = mutableSetOf<String>()

    fun setMetrics(metrics: RecognitionMetrics?) {
        _faceDetectionMetricsState.postValue(metrics)
    }

    fun getNumPeople(): Long = personUseCase.getCount()

    fun updateFaceCounts(detectedCount: Int, storedCount: Long) {
        _faceCountsState.postValue(FaceCounts(detectedCount, storedCount))
    }

    fun updateExpressionCounts(expressionCounts: ImageVectorUseCase.ExpressionCounts) {
        _expressionCountsState.postValue(expressionCounts)
    }

    fun updateGenderCounts(genderCounts: ImageVectorUseCase.GenderCounts) {
        _genderCountsState.postValue(genderCounts)
    }

    fun updateAgeGroupCounts(ageGroupCounts: ImageVectorUseCase.AgeGroupCounts) {
        _ageGroupCountsState.postValue(ageGroupCounts)
    }

    fun updateStoredGenderCounts(genderCounts: ImageVectorUseCase.GenderCounts) {
        _storedGenderCountsState.postValue(genderCounts)
    }

    fun updateStoredAgeGroupCounts(ageGroupCounts: ImageVectorUseCase.AgeGroupCounts) {
        _storedAgeGroupCountsState.postValue(ageGroupCounts)
    }

    fun updateStoredExpressionCounts(expressionCounts: ImageVectorUseCase.ExpressionCounts) {
        _storedExpressionCountsState.postValue(expressionCounts)
    }

    fun resetAllFaceCounts() {
        detectedFaceNames.clear()
        personUseCase.clearAllPeople()
        imageVectorUseCase.clearAllPeople()
        _faceCountsState.postValue(FaceCounts(0, 0))
        _expressionCountsState.postValue(ImageVectorUseCase.ExpressionCounts())
        _genderCountsState.postValue(ImageVectorUseCase.GenderCounts())
        _ageGroupCountsState.postValue(ImageVectorUseCase.AgeGroupCounts())
        _storedGenderCountsState.postValue(ImageVectorUseCase.GenderCounts())
        _storedAgeGroupCountsState.postValue(ImageVectorUseCase.AgeGroupCounts())
        _storedExpressionCountsState.postValue(ImageVectorUseCase.ExpressionCounts())
        _faceDetectionMetricsState.postValue(null)
    }
}
data class FaceCounts(val detectedCount: Int, val storedCount: Long)

class MainActivityViewModelFactory(
    private val context: Context
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MainActivityViewModel::class.java)) {

            val personDB = PersonDB(context)
            val personUseCase = PersonUseCase(personDB)

            val imageVectorUseCase = ImageVectorUseCase(
                mediapipeFaceDetector = MediapipeFaceDetector(context),
                faceSpoofDetector = FaceSpoofDetector(context),
                imagesVectorDB = ImagesVectorDB(context),
                faceNet = FaceNet(context),
                context
            )

            return MainActivityViewModel(personUseCase, imageVectorUseCase) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}