package com.example.demoapplication.domain

import com.example.demoapplication.data.PersonDB
import com.example.demoapplication.data.PersonRecord
import kotlinx.coroutines.flow.Flow

class PersonUseCase(private val personDB: PersonDB) {

    fun addPerson(name: String, numImages: Long): Long =
        personDB.addPerson(
            PersonRecord(
                personName = name,
                numImages = numImages,
                addTime = System.currentTimeMillis()
            )
        )

    fun removePerson(id: Long) =
        personDB.removePerson(id)

    fun getAll(): Flow<List<PersonRecord>> =
        personDB.getAll()

    fun getCount(): Long =
        personDB.getCount()

    fun clearAllPeople() =
        personDB.clearAll()
}