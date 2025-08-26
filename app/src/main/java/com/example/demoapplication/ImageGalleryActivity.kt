package com.example.demoapplication

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.ImageView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.window.layout.WindowMetricsCalculator
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions
import java.io.File

class ImageGalleryActivity : AppCompatActivity() {
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: GalleryAdapter
    private val imageList = mutableListOf<File>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_image_gallery)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        recyclerView = findViewById(R.id.recyclerViewGallery)
        recyclerView.layoutManager = GridLayoutManager(this, 3)

        adapter = GalleryAdapter(this, imageList) { file ->
            showImagePreviewDialog(this, file.absolutePath)
        }
        recyclerView.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        loadAllImages()   // ✅ Reload images every time activity is resumed
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun loadAllImages() {
        val photoFolder = File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "photos")
        imageList.clear()
        if (photoFolder.exists()) {
            photoFolder.listFiles()
                ?.filter { it.isFile} // ✅ only originals
                ?.sortedByDescending { it.lastModified() }
                ?.forEach { file ->
                    imageList.add(file) // ✅ Add all images
                    val sizeKB = file.length() / 1024
                    Log.d("ImageGallery", "Image: ${file.name}, Size: $sizeKB KB")
                }
        }
        adapter.notifyDataSetChanged() // ✅ Refresh RecyclerView
    }
}
class GalleryAdapter(private val context: Context, private val images: List<File>, private val onImageClick: (File) -> Unit) :
    RecyclerView.Adapter<GalleryAdapter.GalleryViewHolder>() {

    class GalleryViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val imageView: ImageView = view.findViewById(R.id.imageItem)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GalleryViewHolder {
        val view = LayoutInflater.from(context).inflate(R.layout.item_image, parent, false)
        return GalleryViewHolder(view)
    }

    override fun onBindViewHolder(holder: GalleryViewHolder, position: Int) {
        val file = images[position]
        Glide.with(context)
            .load(file)
            .centerCrop()
            .into(holder.imageView)
        // ✅ Click listener
        holder.imageView.setOnClickListener {
            onImageClick(file)
        }
    }

    override fun getItemCount(): Int = images.size
}
fun showImagePreviewDialog(context: Context, fileUrl: String) {
    val dialog = Dialog(context)
    dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
    dialog.setContentView(R.layout.dialog_preview)
    dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

    val windowMetrics = WindowMetricsCalculator.getOrCreate().computeCurrentWindowMetrics(context)
    val currentBounds = windowMetrics.bounds
    val width = currentBounds.width()
    dialog.window?.setLayout(width-100, ViewGroup.LayoutParams.WRAP_CONTENT)

    val ivPreview = dialog.findViewById<ImageView>(R.id.ivPreview)
    val ivClose = dialog.findViewById<ImageView>(R.id.btnClose)

    ivPreview.visibility = View.VISIBLE
    ivPreview.setImage(fileUrl)

    ivClose.setOnClickListener { dialog.dismiss() }
    dialog.show()
}
fun ImageView.setImage(url: Any, isCenterCrop: Boolean = false, isUser: Boolean = false) {
    val errorImage = when (isUser) {
        true -> R.drawable.ic_launcher_background
        false -> R.drawable.error
    }
    when {
        isCenterCrop -> Glide.with(this).load(url).apply(RequestOptions().circleCrop().error(errorImage)).into(this)
        else -> Glide.with(this).load(url).apply(RequestOptions().error(errorImage)).into(this)
    }
}