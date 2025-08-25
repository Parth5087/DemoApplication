package com.example.demoapplication.utils

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object ImageUtils {
    fun compressImage(file: File) {
        try {
            // Print original size
            val originalSize = file.length() / 1024 // in KB
            Log.d("ImageCompress", "Original size: $originalSize KB")

            // Decode with reduced resolution to avoid huge memory usage
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
                BitmapFactory.decodeFile(file.absolutePath, this)

                // Calculate inSampleSize to scale down image
                val REQUIRED_SIZE = 800 // target width/height
                var scale = 1
                while ((outWidth / scale >= REQUIRED_SIZE) && (outHeight / scale >= REQUIRED_SIZE)) {
                    scale *= 2
                }
                inSampleSize = scale
                inJustDecodeBounds = false
            }

            val bitmap = BitmapFactory.decodeFile(file.absolutePath, options)

            // Create compressed file
            val compressedFile = File(file.parent, "COMP_${file.name}")
            FileOutputStream(compressedFile).use {
                bitmap?.compress(Bitmap.CompressFormat.JPEG, 50, it) // 50% quality for max compression
            }

            // Delete original
            file.delete()
            compressedFile.renameTo(file)

            // Print compressed size
            val compressedSize = file.length() / 1024 // in KB
            Log.d("ImageCompress", "Compressed size: $compressedSize KB")

        } catch (e: Exception) {
            e.printStackTrace()
            Log.e("ImageCompress", "Compression failed: ${e.message}")
        }
    }

    fun compressImageHighQuality(file: File) {
        try {
            val originalSize = file.length() / 1024
            Log.d("ImageCompress", "Original size: $originalSize KB")

            // Decode image dimensions first
            val options = BitmapFactory.Options()
            options.inJustDecodeBounds = true
            BitmapFactory.decodeFile(file.absolutePath, options)

            // Scale down only if very large (e.g., >2000px width or height)
            var scale = 1
            val REQUIRED_SIZE = 1200
            while ((options.outWidth / scale >= REQUIRED_SIZE) && (options.outHeight / scale >= REQUIRED_SIZE)) {
                scale *= 2
            }

            val options2 = BitmapFactory.Options()
            options2.inSampleSize = scale
            val bitmap = BitmapFactory.decodeFile(file.absolutePath, options2)

            // Compress at high quality (90%)
            val compressedFile = File(file.parent, "COMP_${file.name}")
            FileOutputStream(compressedFile).use {
                bitmap?.compress(Bitmap.CompressFormat.JPEG, 90, it)
            }

            file.delete()
            compressedFile.renameTo(file)

            val compressedSize = file.length() / 1024
            Log.d("ImageCompress", "Compressed size (High-Quality): $compressedSize KB")

        } catch (e: Exception) {
            e.printStackTrace()
            Log.e("ImageCompress", "Compression failed: ${e.message}")
        }
    }

    fun zipFiles(files: List<File>, zipFile: File) {
        ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { zos ->
            files.forEach { file ->
                FileInputStream(file).use { fis ->
                    val entry = ZipEntry(file.name)
                    zos.putNextEntry(entry)
                    fis.copyTo(zos, 1024)
                }
            }
        }
        Log.d("ZIP", "ZIP created: ${zipFile.absolutePath} (${zipFile.length() / 1024} KB)")
    }
}