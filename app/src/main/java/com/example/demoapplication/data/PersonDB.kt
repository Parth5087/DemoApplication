package com.example.demoapplication.data

import android.content.Context
import io.objectbox.kotlin.boxFor
import io.objectbox.kotlin.flow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn

class PersonDB(context: Context) {

//    private val store = MyObjectBox.builder()
//            .androidContext(context)
//            .build()

    private val personBox = ObjectBoxStore.store.boxFor<PersonRecord>()

    fun addPerson(person: PersonRecord): Long =
        personBox.put(person)

    fun removePerson(personID: Long) {
        personBox.remove(personID)
    }

    fun getCount(): Long =
        personBox.count()

    fun clearAll() {
        personBox.removeAll()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun getAll(): Flow<MutableList<PersonRecord>> =
        personBox.query(PersonRecord_.personID.notNull())
            .build()
            .flow()
            .flowOn(Dispatchers.IO)
}