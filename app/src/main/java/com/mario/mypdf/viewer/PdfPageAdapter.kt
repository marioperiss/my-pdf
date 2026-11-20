package com.mario.mypdf.viewer

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.github.chrisbanes.photoview.PhotoView

class PdfPageAdapter(private val renderer: PdfRenderer) :
    RecyclerView.Adapter<PdfPageAdapter.PageViewHolder>() {

    class PageViewHolder(val photoView: PhotoView) : RecyclerView.ViewHolder(photoView)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
        val photoView = PhotoView(parent.context)
        photoView.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        photoView.maximumScale = 10f
        return PageViewHolder(photoView)
    }

    override fun getItemCount(): Int = renderer.pageCount

    override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
        val page = renderer.openPage(position)

        val bitmap = Bitmap.createBitmap(
            page.width,
            page.height,
            Bitmap.Config.ARGB_8888
        )

        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        holder.photoView.setImageBitmap(bitmap)

        page.close()
    }
}

