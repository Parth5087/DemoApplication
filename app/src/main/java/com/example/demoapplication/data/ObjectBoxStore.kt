package com.example.demoapplication.data

import android.content.Context
import io.objectbox.BoxStore

object ObjectBoxStore {

    lateinit var store: BoxStore
        private set

    fun init(context: Context) {
        store = MyObjectBox.builder()
            .androidContext(context)
            .build()
    }
}