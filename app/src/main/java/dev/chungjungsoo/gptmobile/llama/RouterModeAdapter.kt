package dev.chungjungsoo.gptmobile.llama

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import dev.chungjungsoo.gptmobile.R

class RouterModeAdapter(
    private var models: List<RouterModel>,
    private var onModelClick: ((RouterModel) -> Unit)? = null
) : RecyclerView.Adapter<RouterModeAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView = view.findViewById(R.id.tvModelName)
        val tvType: TextView = view.findViewById(R.id.tvModelType)
        val tvStatus: TextView = view.findViewById(R.id.tvModelStatus)
        val tvAliases: TextView = view.findViewById(R.id.tvModelAliases)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_router_model, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val model = models[position]
        holder.tvName.text = model.name
        holder.tvType.text = model.type
        holder.tvStatus.text = model.status
        holder.tvAliases.text = model.aliases.joinToString(", ")

        holder.itemView.setOnClickListener {
            onModelClick?.invoke(model)
        }
    }

    override fun getItemCount(): Int = models.size

    fun setOnModelClickListener(listener: (RouterModel) -> Unit) {
        onModelClick = listener
    }

    fun updateData(newModels: List<RouterModel>) {
        models = newModels
        notifyDataSetChanged()
    }
}
