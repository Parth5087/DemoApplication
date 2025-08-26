package com.example.demoapplication.utils

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
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
            // Ensure parent directory is valid
            val parent = file.parentFile
            if (parent == null) {
                Log.e("ImageCompress", "No parent for ${file.absolutePath}")
                return
            }
            if (parent.exists() && !parent.isDirectory) {
                // If a file exists where a dir should be → replace it with a directory
                parent.delete()
            }
            if (!parent.exists()) parent.mkdirs()

            val originalSize = file.length() / 1024
            Log.d("ImageCompress", "Original size: $originalSize KB")

            // Read bounds
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                Log.e("ImageCompress", "Decode bounds failed for ${file.absolutePath}")
                return
            }

            // Downscale if large
            var scale = 1
            val REQUIRED_SIZE = 1200
            while ((bounds.outWidth / scale >= REQUIRED_SIZE) && (bounds.outHeight / scale >= REQUIRED_SIZE)) {
                scale *= 2
            }

            val opts = BitmapFactory.Options().apply { inSampleSize = scale }
            val bitmap = BitmapFactory.decodeFile(file.absolutePath, opts)
            if (bitmap == null) {
                Log.e("ImageCompress", "Bitmap decode failed for ${file.absolutePath}")
                return
            }

            val compressedFile = File(parent, "COMP_${file.name}")
            FileOutputStream(compressedFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                try { out.fd.sync() } catch (_: Throwable) {}
            }
            bitmap.recycle()

            // Replace original
            if (!file.delete()) Log.w("ImageCompress", "Failed to delete original: ${file.name}")
            if (!compressedFile.renameTo(file)) {
                Log.w("ImageCompress", "Rename failed; keeping COMP_ file.")
            }

            val compressedSize = file.length() / 1024
            Log.d("ImageCompress", "Compressed size (High-Quality): $compressedSize KB")
        } catch (e: Exception) {
            Log.e("ImageCompress", "Compression failed: ${e.message}", e)
        }
    }

    fun convertJpgToWebP(
        jpgFile: File,
        quality: Int = 80,   // 60–85 good range
        maxDim: Int = 1200,  // downscale largest side (reduces size a lot)
        deleteJpg: Boolean = true
    ): File? {
        try {
            // read bounds
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(jpgFile.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            // compute sample factor
            var sample = 1
            while ((bounds.outWidth / sample) > maxDim || (bounds.outHeight / sample) > maxDim) {
                sample *= 2
            }

            // decode bitmap with sampling
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bitmap = BitmapFactory.decodeFile(jpgFile.absolutePath, opts) ?: return null

            val webpFile = File(jpgFile.parentFile, jpgFile.nameWithoutExtension + ".webp")
            FileOutputStream(webpFile).use { out ->
                val fmt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                    Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
                bitmap.compress(fmt, quality, out)
                try { out.fd.sync() } catch (_: Throwable) {}
            }
            bitmap.recycle()

            Log.d("ImageUtils", "WebP saved: ${webpFile.name} (${webpFile.length()} bytes)")

            if (deleteJpg) jpgFile.delete()
            return webpFile
        } catch (e: Exception) {
            Log.e("ImageUtils", "convertJpgToWebP failed: ${e.message}", e)
            return null
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

    fun formatFileSize(bytes: Long): String {
        val kb = 1024
        val mb = kb * 1024
        val gb = mb * 1024

        return when {
            bytes >= gb -> String.format("%.2f GB", bytes.toDouble() / gb)
            bytes >= mb -> String.format("%.2f MB", bytes.toDouble() / mb)
            bytes >= kb -> String.format("%.2f KB", bytes.toDouble() / kb)
            else -> "$bytes B"
        }
    }

}