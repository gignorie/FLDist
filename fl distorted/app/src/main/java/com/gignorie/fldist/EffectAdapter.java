package com.gignorie.fldist;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.SeekBar;
import android.widget.TextView;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.Collections;
import java.util.List;
import java.util.ArrayList;

/**
* Адаптер для RecyclerView, управляющий списком эффектов.
* Реализует ItemTouchHelperAdapter для обработки перетаскивания.
* ВАЖНО: Требует наличия EffectViewHolder и item_effect.xml.
*/
public class EffectAdapter extends RecyclerView.Adapter<EffectViewHolder>
implements ItemTouchHelperAdapter {
	
	private static final String TAG = "EffectAdapter";
	private final EffectEditorActivity activity;
	private final List<EffectData> effectList; // Список, который определяет текущий порядок
	
	// --- Массив меток эффектов для отображения ---
	private final String[] effectNames = {
		"LPF Cutoff", "Ring Mod Freq", "Clip/Decay Speed",
		"Real Bitcrush", "Real Drive", "Real Saturation"
	};
	
	/**
	* Класс для хранения данных одного эффекта.
	*/
	public static class EffectData {
		public final int effectId; // ID эффекта (0..5) - НЕ МЕНЯЕТСЯ
		public int paramLevel;
		public int mixLevel;
		
		public EffectData(int id, int param, int mix) {
			this.effectId = id;
			this.paramLevel = param;
			this.mixLevel = mix;
		}
	}
	
	/**
	* НОВЫЙ КОНСТРУКТОР
	* Инициализирует адаптер, используя текущий порядок из Activity.
	* * @param activity Родительская Activity
	* @param initialOrder Массив ID эффектов в их текущем порядке (загруженный или дефолтный)
	* @param initialParamLevels Глобальные уровни параметров
	* @param initialMixLevels Глобальные уровни микса
	*/
	public EffectAdapter(EffectEditorActivity activity, int[] initialOrder, int[] initialParamLevels, int[] initialMixLevels) {
		this.activity = activity;
		this.effectList = new ArrayList<>();
		
		// Инициализация списка эффектов на основе переданного initialOrder
		for (int id : initialOrder) {
			this.effectList.add(new EffectData(
			id,
			initialParamLevels[id], // Берем уровень по ID, независимо от порядка
			initialMixLevels[id]
			));
		}
		Log.d(TAG, "Adapter initialized with order: " + java.util.Arrays.toString(initialOrder));
	}
	
	// --- Методы RecyclerView.Adapter ---
	
	@NonNull
	@Override
	public EffectViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
		Context context = parent.getContext();
		LayoutInflater inflater = LayoutInflater.from(context);
		// Предполагается, что item_effect.xml существует
		View effectView = inflater.inflate(R.layout.item_effect, parent, false);
		return new EffectViewHolder(effectView);
	}
	
	@Override
	public void onBindViewHolder(@NonNull EffectViewHolder holder, int position) {
		// Получаем данные для текущей позиции в списке
		final EffectData currentEffect = effectList.get(position);
		final int effectId = currentEffect.effectId; // ID DSP-эффекта (0..5)
		
		// 1. Отображение заголовка (с порядковым номером)
		String title = String.format("%d. %s (ID: %d)",
		position + 1,
		effectNames[effectId],
		effectId);
		holder.titleTextView.setText(title);
		
		// 2. Настройка ползунка Параметра
		// Устанавливаем Progress из EffectData (которое обновилось при загрузке)
		holder.paramSeekBar.setProgress(currentEffect.paramLevel);
		updateParamText(holder.paramTextView, effectNames[effectId], currentEffect.paramLevel);
		
		holder.paramSeekBar.setOnSeekBarChangeListener(new SimpleSeekBarListener() {
			@Override
			public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
				if (fromUser) {
					currentEffect.paramLevel = progress;
					// Обновляем массив в Activity, чтобы DSP-логика имела актуальные данные
					activity.paramLevels[effectId] = progress;
					updateParamText(holder.paramTextView, effectNames[effectId], progress);
				}
			}
		});
		
		// 3. Настройка ползунка Mix
		holder.mixSeekBar.setProgress(currentEffect.mixLevel);
		holder.mixTextView.setText("Mix %: " + currentEffect.mixLevel);
		
		holder.mixSeekBar.setOnSeekBarChangeListener(new SimpleSeekBarListener() {
			@Override
			public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
				if (fromUser) {
					currentEffect.mixLevel = progress;
					// Обновляем массив в Activity
					activity.mixLevels[effectId] = progress;
					holder.mixTextView.setText("Mix %: " + progress);
				}
			}
		});
	}
	
	@Override
	public int getItemCount() {
		return effectList.size();
	}
	
	// --- Методы для синхронизации с Activity ---
	
	/**
	* НОВЫЙ МЕТОД: Обновляет внутренний список эффектов (effectList) на основе нового порядка ID
	* и новых уровней, загруженных из SharedPreferences.
	* * @param newOrder Массив ID эффектов в требуемом порядке.
	* @param loadedParamLevels Загруженные уровни параметра.
	* @param loadedMixLevels Загруженные уровни микса.
	*/
	public void updateAdapterOrder(int[] newOrder, int[] loadedParamLevels, int[] loadedMixLevels) {
		if (newOrder.length != effectList.size()) {
			Log.e(TAG, "New order array size mismatch. Cannot load preset.");
			return;
		}
		
		// 1. Создаем временный map для быстрого доступа к объектам EffectData по их ID
		java.util.Map<Integer, EffectData> dataMap = new java.util.HashMap<>();
		for (EffectData data : effectList) {
			dataMap.put(data.effectId, data);
		}
		
		// 2. Очищаем текущий список и перестраиваем его в новом порядке, обновляя уровни
		effectList.clear();
		for (int effectId : newOrder) {
			EffectData data = dataMap.get(effectId);
			if (data != null) {
				// Обновляем уровни в объекте EffectData новыми значениями
				data.paramLevel = loadedParamLevels[effectId];
				data.mixLevel = loadedMixLevels[effectId];
				
				effectList.add(data);
				} else {
				// Это не должно произойти, если константы не менялись
				Log.e(TAG, "Effect ID " + effectId + " not found in data map!");
			}
		}
		
		// 3. Уведомляем RecyclerView о полном изменении данных
		notifyDataSetChanged();
		
		Log.d(TAG, "Adapter list successfully reordered and data updated for preset loading.");
	}
	
	// --- Реализация ItemTouchHelperAdapter для Drag & Drop ---
	
	@Override
	public void onItemMove(int fromPosition, int toPosition) {
		// 1. Меняем местами элементы в списке данных Адаптера
		if (fromPosition < toPosition) {
			for (int i = fromPosition; i < toPosition; i++) {
				Collections.swap(effectList, i, i + 1);
			}
			} else {
			for (int i = fromPosition; i > toPosition; i--) {
				Collections.swap(effectList, i, i - 1);
			}
		}
		
		// 2. Уведомляем RecyclerView об изменении (перерисовка)
		notifyItemMoved(fromPosition, toPosition);
		
		// 3. Обновляем DSP-цепочку в Activity
		// Важно: Эта функция не меняет данные, она только генерирует новый массив effectOrder
		int[] newOrder = getCurrentEffectOrder();
		activity.updateEffectOrder(newOrder);
		
		// 4. Перерисовываем ВСЕ элементы, чтобы обновить их порядковые номера в заголовках (1., 2., 3...)
		notifyDataSetChanged();
	}
	
	/**
	* Генерирует массив ID DSP-эффектов в их текущем порядке.
	*/
	public int[] getCurrentEffectOrder() {
		int[] newOrder = new int[effectList.size()];
		for (int i = 0; i < effectList.size(); i++) {
			newOrder[i] = effectList.get(i).effectId;
		}
		return newOrder;
	}
	
	// --- Вспомогательные методы и классы ---
	
	private void updateParamText(TextView tv, String name, int level) {
		// Используем универсальную метку "Уровень"
		tv.setText(String.format("%s: Уровень %d", name, level));
	}
	
	/**
	* Абстрактный слушатель для упрощения кода (так как не используем Start/Stop Tracking)
	*/
	private static abstract class SimpleSeekBarListener implements SeekBar.OnSeekBarChangeListener {
		@Override
		public void onStartTrackingTouch(SeekBar seekBar) { /* Не используется */ }
		@Override
		public void onStopTrackingTouch(SeekBar seekBar) { /* Не используется */ }
	}
}