package com.margelo.nitro.nitroclouduploader

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri

/**
 * ContentProvider to automatically get application context on app startup
 * This is called before any Activity or Application.onCreate()
 */
class ContextProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        // Store application context globally
        appContext = context?.applicationContext
        println("✅ ContextProvider initialized with context: ${appContext != null}")
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0

    companion object {
        @Volatile
        var appContext: Context? = null
            private set
    }
}

