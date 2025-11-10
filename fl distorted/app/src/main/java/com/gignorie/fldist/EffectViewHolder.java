package com.gignorie.fldist;

import android.view.View;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

public class EffectViewHolder extends RecyclerView.ViewHolder {
	
	public final TextView titleTextView;
	public final TextView paramTextView;
	public final SeekBar paramSeekBar;
	public final TextView mixTextView;
	public final SeekBar mixSeekBar;
	
	// В конструктор передается View, который является контейнером для одного эффекта
	public EffectViewHolder(@NonNull View itemView) {
		super(itemView);
		
		// --- Найти элементы внутри item_effect.xml ---
		
		// Заголовок эффекта (например, "1. LPF Cutoff")
		titleTextView = itemView.findViewById(R.id.effect_item_title);
		
		// Ползунок Параметра и его метка
		paramTextView = itemView.findViewById(R.id.effect_item_param_text);
		paramSeekBar = itemView.findViewById(R.id.effect_item_param_seekbar);
		
		// Ползунок Mix и его метка
		mixTextView = itemView.findViewById(R.id.effect_item_mix_text);
		mixSeekBar = itemView.findViewById(R.id.effect_item_mix_seekbar);
	}
}