package com.gignorie.fldist;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.AsyncTask;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.media.MediaPlayer;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.stream.Collectors;

// Предполагается, что класс WavFile доступен
// import com.gignorie.fldist.WavFile;

public class EffectEditorActivity extends AppCompatActivity {
	
	private static final String TAG = "EffectEditorActivity";
	private String wavFilePath;
	
	// --- КОНСТАНТЫ ID ЭФФЕКТОВ (Индексы массивов) ---
	public static final int FX_LPF_CUTOFF = 0;
	public static final int FX_RING_MOD = 1;
	public static final int FX_CLIP_DECAY = 2;
	public static final int FX_REAL_BITCRUSH = 3;
	public static final int FX_REAL_DRIVE = 4;
	public static final int FX_REAL_SATURATION = 5;
	public static final int NUM_EFFECTS = 6;
	
	// --- ГЛОБАЛЬНЫЕ МАССИВЫ (Обновляются адаптером, считываются DSP) ---
	public final int[] paramLevels = new int[NUM_EFFECTS];
	public final int[] mixLevels = new int[NUM_EFFECTS];
	
	// --- МАССИВ ПОРЯДКА НАНЕСЕНИЯ ЭФФЕКТОВ (Цепочка DSP) ---
	private int[] effectOrder = {
		FX_LPF_CUTOFF, FX_RING_MOD, FX_CLIP_DECAY,
		FX_REAL_BITCRUSH, FX_REAL_DRIVE, FX_REAL_SATURATION
	};
	
	// UI-элементы
	private Button previewButton;
	private Button savePresetButton;
	private Button applyEffectsButton;
	private RecyclerView recyclerView;
	private EffectAdapter effectAdapter;
	
	// Медиаплеер и управление состоянием
	private MediaPlayer mediaPlayer;
	private boolean isPlaying = false;
	private String tempAudioPath = null;
	private PreviewTask currentPreviewTask;
	private ApplyEffectsTask currentApplyTask;
	
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_effect_editor);
		
		// Получаем полный путь
		String fullWavPath = getIntent().getStringExtra("WAV_PATH");
		
		// --- БЛОК ИСПРАВЛЕНИЯ ПУТИ ---
		if (fullWavPath != null) {
			// Удаляем "/My Songs" из пути, если он там присутствует.
			wavFilePath = fullWavPath.replace("/My Songs", "");
			} else {
			wavFilePath = null;
		}
		
		TextView pathTextView = findViewById(R.id.effect_path_text);
		previewButton = findViewById(R.id.button_preview);
		savePresetButton = findViewById(R.id.button_save_settings);
		applyEffectsButton = findViewById(R.id.button_apply_effects);
		
		if (wavFilePath != null) {
			pathTextView.setText("Editing: " + getFileName(wavFilePath));
			} else {
			pathTextView.setText("Error: No file selected.");
		}
		
		// 1. Инициализация массивов начальными значениями (если нужно)
		mixLevels[FX_LPF_CUTOFF] = 100;
		mixLevels[FX_RING_MOD] = 100;
		mixLevels[FX_CLIP_DECAY] = 100;
		
		// 2. ЗАГРУЗКА ПРЕСЕТА (ДО создания адаптера)
		loadEffectPreset();
		
		// 3. Настройка RecyclerView и Adapter
		setupRecyclerView();
		
		previewButton.setOnClickListener(v -> togglePreview());
		savePresetButton.setOnClickListener(v -> saveEffectPreset());
		applyEffectsButton.setOnClickListener(v -> applyEffectsAndOverwrite());
		
		updatePreviewButtonText(); // Обновление текста кнопки
		
		mediaPlayer = new MediaPlayer();
	}
	
	/**
	* Настройка RecyclerView, Adapter и ItemTouchHelper для Drag & Drop.
	*/
	private void setupRecyclerView() {
		recyclerView = findViewById(R.id.effect_chain_recyclerview);
		
		// ⚡ ИСПРАВЛЕНИЕ: Передаем текущий effectOrder (загруженный или дефолтный) в конструктор
		effectAdapter = new EffectAdapter(this, effectOrder, paramLevels, mixLevels);
		recyclerView.setAdapter(effectAdapter);
		recyclerView.setLayoutManager(new LinearLayoutManager(this));
		
		// 3. Настройка ItemTouchHelper для перетаскивания (Drag & Drop)
		ItemTouchHelper.Callback callback =
		new ItemTouchHelper.SimpleCallback(
		// Разрешаем Drag: UP и DOWN
		ItemTouchHelper.UP | ItemTouchHelper.DOWN,
		0) { // Не разрешаем Swipe
			
			@Override
			public boolean onMove(@NonNull RecyclerView recyclerView,
			@NonNull RecyclerView.ViewHolder viewHolder,
			@NonNull RecyclerView.ViewHolder target) {
				
				// Вызываем метод, который меняет список данных в Адаптере
				effectAdapter.onItemMove(viewHolder.getAdapterPosition(),
				target.getAdapterPosition());
				
				return true;
			}
			
			@Override
			public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
				// Не используется
			}
			
			// Визуальная обратная связь при перетаскивании
			@Override
			public void onSelectedChanged(RecyclerView.ViewHolder viewHolder, int actionState) {
				super.onSelectedChanged(viewHolder, actionState);
				if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && viewHolder != null) {
					// Изменение прозрачности при перетаскивании
					viewHolder.itemView.setAlpha(0.7f);
				}
			}
			
			@Override
			public void clearView(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder) {
				super.clearView(recyclerView, viewHolder);
				// Возврат к нормальной прозрачности
				if (viewHolder != null) {
					viewHolder.itemView.setAlpha(1.0f);
				}
			}
		};
		
		ItemTouchHelper touchHelper = new ItemTouchHelper(callback);
		touchHelper.attachToRecyclerView(recyclerView);
	}
	
	/**
	* Публичный метод для обновления порядка эффектов.
	* Вызывается EffectAdapter после успешного Drag & Drop.
	* @param newOrder новый массив ID эффектов.
	*/
	public void updateEffectOrder(int[] newOrder) {
		if (newOrder.length != NUM_EFFECTS) {
			Log.e(TAG, "New effect order length mismatch.");
			return;
		}
		this.effectOrder = newOrder;
		Log.d(TAG, "Effect order updated: " + Arrays.toString(newOrder));
		
		updatePreviewButtonText();
	}
	
	/**
	* Обновляет текст кнопки Preview, чтобы отразить текущий порядок цепочки.
	*/
	private void updatePreviewButtonText() {
		if (previewButton != null) {
			StringBuilder sb = new StringBuilder();
			for (int id : effectOrder) {
				sb.append(id).append("-");
			}
			if (sb.length() > 0) sb.setLength(sb.length() - 1); // Удалить последний дефис
			
			previewButton.setText(isPlaying ? "⏹️ STOP Preview" : "🎧 PREVIEW (Chain: " + sb.toString() + ")");
			previewButton.setEnabled(true);
		}
	}
	
	@Override
	protected void onDestroy() {
		super.onDestroy();
		stopPlayback();
		if (currentPreviewTask != null) {
			currentPreviewTask.cancel(true);
		}
		if (currentApplyTask != null) {
			currentApplyTask.cancel(true);
		}
		if (mediaPlayer != null) {
			mediaPlayer.release();
			mediaPlayer = null;
		}
		// Удаление временного файла предпросмотра
		if (tempAudioPath != null) {
			File tempFile = new File(tempAudioPath);
			if (tempFile.exists()) {
				// Временный файл находится в кеше, удаляем через Java IO
				tempFile.delete();
			}
			tempAudioPath = null;
		}
	}
	
	/**
	* Выполнение Root-команд (su)
	*/
	private String executeRootCommand(String command) {
		Process process = null;
		DataOutputStream os = null;
		StringBuilder output = new StringBuilder();
		try {
			process = Runtime.getRuntime().exec("su");
			os = new DataOutputStream(process.getOutputStream());
			
			os.writeBytes(command + "\n");
			os.writeBytes("exit\n");
			os.flush();
			
			BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
			String line;
			while ((line = reader.readLine()) != null) {
				output.append(line).append("\n");
			}
			
			BufferedReader errorReader = new BufferedReader(new InputStreamReader(process.getErrorStream()));
			while ((line = errorReader.readLine()) != null) {
				Log.e(TAG, "Root Error Stream: " + line + "\n ");
			}
			
			process.waitFor();
			if (process.exitValue() != 0) {
				return "ERROR: Command failed with code " + process.exitValue() + "\n" + output.toString();
			}
			
			} catch (Exception e) {
			Log.e(TAG, "Failed to execute root command: " + e.getMessage(), e);
			return "ERROR: Exception executing command: " + e.getMessage();
			} finally {
			try {
				if (os != null) os.close();
				if (process != null) process.destroy();
			} catch (Exception e) { /* Ignored */ }
		}
		return output.toString().trim();
	}
	
	private void togglePreview() {
		if (isPlaying) {
			stopPlayback();
			} else {
			if (currentPreviewTask != null && currentPreviewTask.getStatus() == AsyncTask.Status.RUNNING) {
				currentPreviewTask.cancel(true);
			}
			// Важно: передаем КОПИЮ текущего порядка в задачу
			currentPreviewTask = new PreviewTask(this, Arrays.copyOf(effectOrder, effectOrder.length));
			currentPreviewTask.execute(wavFilePath);
			previewButton.setText("🔄 Processing...");
			previewButton.setEnabled(false);
		}
	}
	
	private void startPlayback(String path) {
		if (path == null) {
			Toast.makeText(this, "Failed to process file.", Toast.LENGTH_LONG).show();
			updatePreviewButtonText();
			return;
		}
		
		tempAudioPath = path;
		
		try {
			mediaPlayer.reset();
			mediaPlayer.setDataSource(tempAudioPath);
			mediaPlayer.prepare();
			mediaPlayer.start();
			
			isPlaying = true;
			updatePreviewButtonText(); // Обновит на "STOP"
			
			mediaPlayer.setOnCompletionListener(mp -> stopPlayback());
			
			Toast.makeText(this, "Playback started.", Toast.LENGTH_SHORT).show();
			
			} catch (IOException e) {
			Log.e(TAG, "Error setting up playback: " + e.getMessage(), e);
			Toast.makeText(this, "Playback setup failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
			stopPlayback();
		}
	}
	
	private void stopPlayback() {
		if (mediaPlayer != null && mediaPlayer.isPlaying()) {
			mediaPlayer.stop();
		}
		isPlaying = false;
		updatePreviewButtonText(); // Обновит на "PREVIEW"
	}
	
	private String getFileName(String fullPath) {
		if (fullPath == null) return "N/A";
		return new File(fullPath).getName();
	}
	
	// =====================================================================
	// МЕТОДЫ ДЛЯ СОХРАНЕНИЯ И ЗАГРУЗКИ ПРЕСЕТОВ
	// =====================================================================
	
	/**
	* Сохраняет текущую цепочку эффектов, параметры и микс как пресет.
	*/
	private void saveEffectPreset() {
		String presetName = "DefaultChainPreset";
		
		// Преобразуем массивы в строки для сохранения
		String orderStr = Arrays.stream(effectOrder).mapToObj(String::valueOf).collect(Collectors.joining(","));
		String paramStr = Arrays.stream(paramLevels).mapToObj(String::valueOf).collect(Collectors.joining(","));
		String mixStr = Arrays.stream(mixLevels).mapToObj(String::valueOf).collect(Collectors.joining(","));
		
		getSharedPreferences("EffectPresets", MODE_PRIVATE)
		.edit()
		.putString(presetName + "_ORDER", orderStr)
		.putString(presetName + "_PARAM", paramStr)
		.putString(presetName + "_MIX", mixStr)
		.apply();
		
		Toast.makeText(this, "Preset '" + presetName + "' saved successfully!", Toast.LENGTH_SHORT).show();
		Log.d(TAG, "Preset saved. Order: " + orderStr);
	}
	
	/**
	* Загружает сохраненную цепочку эффектов, параметры и микс из SharedPreferences.
	*/
	private void loadEffectPreset() {
		String presetName = "DefaultChainPreset";
		SharedPreferences prefs = getSharedPreferences("EffectPresets", MODE_PRIVATE);
		
		String orderStr = prefs.getString(presetName + "_ORDER", null);
		String paramStr = prefs.getString(presetName + "_PARAM", null);
		String mixStr = prefs.getString(presetName + "_MIX", null);
		
		if (orderStr != null && paramStr != null && mixStr != null) {
			try {
				// 1. Загрузка effectOrder
				String[] orderParts = orderStr.split(",");
				if (orderParts.length != NUM_EFFECTS) {
					Log.e(TAG, "Loaded order array size mismatch. Using defaults.");
					return;
				}
				for (int i = 0; i < NUM_EFFECTS; i++) {
					effectOrder[i] = Integer.parseInt(orderParts[i]);
				}
				
				// 2. Загрузка Param и Mix Levels
				String[] paramParts = paramStr.split(",");
				String[] mixParts = mixStr.split(",");
				
				if (paramParts.length == NUM_EFFECTS && mixParts.length == NUM_EFFECTS) {
					for (int i = 0; i < NUM_EFFECTS; i++) {
						paramLevels[i] = Integer.parseInt(paramParts[i]);
						mixLevels[i] = Integer.parseInt(mixParts[i]);
					}
					
					// Ключевое исправление: Если адаптер уже создан (например, после поворота)
					// мы вызываем метод синхронизации в адаптере.
					if (effectAdapter != null) {
						effectAdapter.updateAdapterOrder(effectOrder, paramLevels, mixLevels);
					}
					
					Log.d(TAG, "Preset '" + presetName + "' loaded successfully.");
					Toast.makeText(this, "Preset '" + presetName + "' loaded.", Toast.LENGTH_SHORT).show();
					
					// Обновляем текст кнопки, чтобы отразить новый порядок
					updatePreviewButtonText();
					} else {
					Log.e(TAG, "Loaded param/mix array size mismatch.");
				}
				} catch (NumberFormatException e) {
				Log.e(TAG, "Error parsing preset data: " + e.getMessage());
			}
			} else {
			Log.d(TAG, "No preset found, using default settings.");
		}
	}
	
	/**
	* Запускает фоновую задачу для окончательного применения эффектов и перезаписи файла.
	*/
	private void applyEffectsAndOverwrite() {
		if (wavFilePath == null) {
			Toast.makeText(this, "Error: No file selected for applying effects.", Toast.LENGTH_LONG).show();
			return;
		}
		
		// Останавливаем любое текущее воспроизведение/предварительный просмотр
		stopPlayback();
		if (currentPreviewTask != null && currentPreviewTask.getStatus() == AsyncTask.Status.RUNNING) {
			currentPreviewTask.cancel(true);
		}
		if (currentApplyTask != null && currentApplyTask.getStatus() == AsyncTask.Status.RUNNING) {
			Toast.makeText(this, "Processing is already running.", Toast.LENGTH_SHORT).show();
			return;
		}
		
		// Предупреждение пользователю
		new AlertDialog.Builder(this)
		.setTitle("Apply and Overwrite")
		.setMessage("This will permanently overwrite the original file:\n" + getFileName(wavFilePath) + "\nAre you sure?")
		.setPositiveButton("YES", (dialog, which) -> {
			// Важно: передаем КОПИИ текущего порядка и уровней
			int[] effectOrderCopy = Arrays.copyOf(effectOrder, effectOrder.length);
			int[] paramLevelsCopy = Arrays.copyOf(paramLevels, paramLevels.length);
			int[] mixLevelsCopy = Arrays.copyOf(mixLevels, mixLevels.length);
			
			currentApplyTask = new ApplyEffectsTask(
			this,
			effectOrderCopy,
			paramLevelsCopy,
			mixLevelsCopy
			);
			currentApplyTask.execute(wavFilePath); // Передаем ПУТЬ К ОРИГИНАЛЬНОМУ ФАЙЛУ
			
			applyEffectsButton.setText("🔄 APPLYING...");
			applyEffectsButton.setEnabled(false);
			savePresetButton.setEnabled(false);
		})
		.setNegativeButton("NO", null)
		.show();
	}
	
	// =====================================================================
	// ВНУТРЕННИЙ КЛАСС ДЛЯ ФОНОВОЙ ОБРАБОТКИ (Динамический DSP-Цепочка PREVIEW)
	// =====================================================================
	
	private static class PreviewTask extends AsyncTask<String, Void, String> {
		private final WeakReference<EffectEditorActivity> activityReference;
		// Порядок эффектов, который был активен на момент запуска задачи
		private final int[] currentEffectOrder;
		
		PreviewTask(EffectEditorActivity context, int[] effectOrder) {
			activityReference = new WeakReference<>(context);
			this.currentEffectOrder = effectOrder;
		}
		
		private void mixSignal(double[] original, double[] processed, int mixLevel) {
			if (mixLevel >= 100) return;
			
			double wet = mixLevel / 100.0;
			double dry = 1.0 - wet;
			
			for (int i = 0; i < original.length; i++) {
				processed[i] = (processed[i] * wet) + (original[i] * dry);
			}
		}
		
		private double[] copyBuffer(double[] source) {
			double[] destination = new double[source.length];
			System.arraycopy(source, 0, destination, 0, source.length);
			return destination;
		}
		
		/**
		* Универсальный метод для применения одного DSP-эффекта.
		*/
		private void applySingleEffect(double[] buffer, double[] dryBuffer,
		int effectId, int paramLevel, int mixLevel,
		long sampleRate) {
			
			if (mixLevel <= 0) return;
			
			double[] wetBuffer = copyBuffer(buffer);
			
			// --- Логика DSP-эффектов ---
			switch (effectId) {
				case FX_LPF_CUTOFF:
				// 1. Low-Pass Filter
				double minCutoff = 100.0; double maxCutoff = 3000.0;
				double cutoffFreq = minCutoff + (maxCutoff - minCutoff) * (paramLevel / 100.0);
				double RC = 1.0 / (cutoffFreq * 2.0 * Math.PI);
				double alpha = 1.0 / (RC * sampleRate + 1.0);
				double lastOutput = 0.0;
				for (int i = 0; i < wetBuffer.length; i++) {
					lastOutput = alpha * wetBuffer[i] + (1.0 - alpha) * lastOutput;
					wetBuffer[i] = lastOutput;
				}
				break;
				
				case FX_RING_MOD:
				// 2. Ring Modulation
				double minModFreq = 50.0; double maxModFreq = 500.0;
				double modFreq = minModFreq + (maxModFreq - minModFreq) * (paramLevel / 100.0);
				double modPhase = 0.0;
				double modIncrement = 2.0 * Math.PI * modFreq / sampleRate;
				for (int i = 0; i < wetBuffer.length; i++) {
					wetBuffer[i] *= Math.sin(modPhase);
					modPhase += modIncrement;
					if (modPhase >= 2.0 * Math.PI) modPhase -= 2.0 * Math.PI;
				}
				break;
				
				case FX_CLIP_DECAY:
				// 3. Hard Clipping и Envelope
				// Hard Clipping
				double minHardDrive = 1.0; double maxHardDrive = 5.0;
				double hardDrive = minHardDrive + (maxHardDrive - minHardDrive) * (paramLevel / 100.0);
				double threshold = 1.0 / hardDrive;
				for (int i = 0; i < wetBuffer.length; i++) {
					double x = wetBuffer[i];
					if (x > threshold) wetBuffer[i] = threshold; else if (x < -threshold) wetBuffer[i] = -threshold;
				}
				// Envelope
				double attackTime = 0.05; double minDecayTime = 0.1; double maxDecayTime = 0.5;
				double decayTime = maxDecayTime - (maxDecayTime - minDecayTime) * (paramLevel / 100.0);
				int attackSamples = (int) (attackTime * sampleRate);
				int decaySamples = (int) (decayTime * sampleRate);
				int startDecay = Math.min(attackSamples, wetBuffer.length / 4);
				for (int i = 0; i < wetBuffer.length; i++) {
					double env = 1.0;
					if (i < attackSamples) env = (double) i / attackSamples;
					else if (i < startDecay + decaySamples) env = 1.0 - (double) (i - startDecay) / decaySamples;
					else env = 0.05;
					if (env < 0) env = 0;
					wetBuffer[i] *= env;
				}
				break;
				
				case FX_REAL_DRIVE:
				// 4. Real Drive (Усиление)
				double overallDrive = 1.0 + paramLevel / 50.0;
				for (int i = 0; i < wetBuffer.length; i++) {
					wetBuffer[i] *= overallDrive;
				}
				break;
				
				case FX_REAL_SATURATION:
				// 5. Real Saturation (Soft Clipping через Tanh)
				double satAmount = 1.0 + paramLevel / 20.0;
				for (int i = 0; i < wetBuffer.length; i++) {
					wetBuffer[i] = Math.tanh(wetBuffer[i] * satAmount);
				}
				break;
				
				case FX_REAL_BITCRUSH:
				// 6. Real Bitcrush (Квантование)
				int effectiveBitDepth = Math.max(1, 16 - paramLevel / 6);
				double maxQuantization = Math.pow(2, effectiveBitDepth) - 1;
				for (int i = 0; i < wetBuffer.length; i++) {
					double normalizedSample = wetBuffer[i];
					wetBuffer[i] = Math.round(normalizedSample * maxQuantization) / maxQuantization;
				}
				break;
			}
			
			// Смешивание и копирование обратно в основной буфер
			mixSignal(dryBuffer, wetBuffer, mixLevel);
			System.arraycopy(wetBuffer, 0, buffer, 0, buffer.length);
		}
		
		@Override
		protected String doInBackground(String... params) {
			EffectEditorActivity activity = activityReference.get();
			if (activity == null || activity.isFinishing()) return null;
			
			String originalPath = params[0];
			File tempFile = null;
			
			try {
				// --- 1. Создание и копирование временного файла в КЕШ приложения (Root) ---
				String timestamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(new java.util.Date());
				tempFile = new File(activity.getCacheDir(), "temp_preview_" + timestamp + ".wav");
				String tempPath = tempFile.getAbsolutePath();
				
				// Экранирование пути для shell
				String escapedOriginalPath = originalPath.replace("'", "'\\''");
				String escapedTempPath = tempPath.replace("'", "'\\''");
				
				// Копирование оригинала во временный файл в кеше (Root)
				String command = "cp -f '" + escapedOriginalPath + "' '" + escapedTempPath + "' && chmod 666 '" + escapedTempPath + "'";
				String result = activity.executeRootCommand(command);
				if (result.startsWith("ERROR")) {
					Log.e(TAG, "Root copy failed for preview: " + result);
					return null;
				}
				
				// --- 2. Загрузка WAV (теперь файл в кеше, доступен Java IO) ---
				WavFile wav = WavFile.openWavFile(tempFile);
				int numFrames = (int) wav.getNumFrames();
				long sampleRate = wav.getSampleRate();
				int numChannels = wav.getNumChannels();
				int validBits = wav.getValidBits();
				
				double[] buffer = new double[numFrames * numChannels];
				wav.readFrames(buffer, numFrames);
				wav.close();
				
				// --- 3. Динамическая DSP-ЦЕПОЧКА ---
				for (int effectId : currentEffectOrder) {
					// Читаем уровни из глобальных массивов Activity
					int paramLevel = activity.paramLevels[effectId];
					int mixLevel = activity.mixLevels[effectId];
					
					double[] dryBuffer = copyBuffer(buffer);
					
					applySingleEffect(
					buffer,
					dryBuffer,
					effectId,
					paramLevel,
					mixLevel,
					sampleRate
					);
				}
				
				// --- 4. Сохранение обратно во временный WAV ---
				WavFile outWav = WavFile.newWavFile(tempFile, numChannels, buffer.length, validBits, sampleRate);
				outWav.writeFrames(buffer, buffer.length);
				outWav.close();
				
				return tempPath;
				
				} catch (Exception e) {
				Log.e(TAG, "Error in background copy/processing: " + e.getMessage(), e);
				// Удаляем tempFile, если он был создан
				if (tempFile != null && tempFile.exists()) tempFile.delete();
				return null;
			}
		}
		
		@Override
		protected void onPostExecute(String tempPath) {
			EffectEditorActivity activity = activityReference.get();
			if (activity == null || activity.isFinishing()) return;
			
			if (tempPath != null) {
				activity.startPlayback(tempPath);
				} else {
				activity.stopPlayback();
				Toast.makeText(activity, "File processing failed. Check Root status and WavFile class.", Toast.LENGTH_LONG).show();
			}
			activity.previewButton.setEnabled(true);
		}
		
		@Override
		protected void onCancelled() {
			super.onCancelled();
			EffectEditorActivity activity = activityReference.get();
			if (activity != null && !activity.isFinishing()) {
				activity.stopPlayback();
				activity.previewButton.setEnabled(true);
			}
		}
	}
	
	// =====================================================================
	// ВНУТРЕННИЙ КЛАСС ДЛЯ ОКОНЧАТЕЛЬНОЙ ОБРАБОТКИ (ПЕРЕЗАПИСЬ)
	// =====================================================================
	
	private static class ApplyEffectsTask extends AsyncTask<String, Void, Boolean> {
		private final WeakReference<EffectEditorActivity> activityReference;
		private final int[] currentEffectOrder;
		private final int[] currentParamLevels;
		private final int[] currentMixLevels;
		
		ApplyEffectsTask(EffectEditorActivity context, int[] effectOrder, int[] paramLevels, int[] mixLevels) {
			activityReference = new WeakReference<>(context);
			this.currentEffectOrder = effectOrder;
			this.currentParamLevels = paramLevels;
			this.currentMixLevels = mixLevels;
		}
		
		// --- DSP-методы (для автономной работы) ---
		
		private void mixSignal(double[] original, double[] processed, int mixLevel) {
			if (mixLevel >= 100) return;
			
			double wet = mixLevel / 100.0;
			double dry = 1.0 - wet;
			
			for (int i = 0; i < original.length; i++) {
				processed[i] = (processed[i] * wet) + (original[i] * dry);
			}
		}
		
		private double[] copyBuffer(double[] source) {
			double[] destination = new double[source.length];
			System.arraycopy(source, 0, destination, 0, source.length);
			return destination;
		}
		
		/**
		* Универсальный метод для применения одного DSP-эффекта.
		*/
		private void applySingleEffect(double[] buffer, double[] dryBuffer,
		int effectId, int paramLevel, int mixLevel,
		long sampleRate) {
			
			if (mixLevel <= 0) return;
			
			double[] wetBuffer = copyBuffer(buffer);
			
			// --- Логика DSP-эффектов ---
			switch (effectId) {
				case FX_LPF_CUTOFF:
				// 1. Low-Pass Filter
				double minCutoff = 100.0; double maxCutoff = 3000.0;
				double cutoffFreq = minCutoff + (maxCutoff - minCutoff) * (paramLevel / 100.0);
				double RC = 1.0 / (cutoffFreq * 2.0 * Math.PI);
				double alpha = 1.0 / (RC * sampleRate + 1.0);
				double lastOutput = 0.0;
				for (int i = 0; i < wetBuffer.length; i++) {
					lastOutput = alpha * wetBuffer[i] + (1.0 - alpha) * lastOutput;
					wetBuffer[i] = lastOutput;
				}
				break;
				
				case FX_RING_MOD:
				// 2. Ring Modulation
				double minModFreq = 50.0; double maxModFreq = 500.0;
				double modFreq = minModFreq + (maxModFreq - minModFreq) * (paramLevel / 100.0);
				double modPhase = 0.0;
				double modIncrement = 2.0 * Math.PI * modFreq / sampleRate;
				for (int i = 0; i < wetBuffer.length; i++) {
					wetBuffer[i] *= Math.sin(modPhase);
					modPhase += modIncrement;
					if (modPhase >= 2.0 * Math.PI) modPhase -= 2.0 * Math.PI;
				}
				break;
				
				case FX_CLIP_DECAY:
				// 3. Hard Clipping и Envelope
				// Hard Clipping
				double minHardDrive = 1.0; double maxHardDrive = 5.0;
				double hardDrive = minHardDrive + (maxHardDrive - minHardDrive) * (paramLevel / 100.0);
				double threshold = 1.0 / hardDrive;
				for (int i = 0; i < wetBuffer.length; i++) {
					double x = wetBuffer[i];
					if (x > threshold) wetBuffer[i] = threshold; else if (x < -threshold) wetBuffer[i] = -threshold;
				}
				// Envelope
				double attackTime = 0.05; double minDecayTime = 0.1; double maxDecayTime = 0.5;
				double decayTime = maxDecayTime - (maxDecayTime - minDecayTime) * (paramLevel / 100.0);
				int attackSamples = (int) (attackTime * sampleRate);
				int decaySamples = (int) (decayTime * sampleRate);
				int startDecay = Math.min(attackSamples, wetBuffer.length / 4);
				for (int i = 0; i < wetBuffer.length; i++) {
					double env = 1.0;
					if (i < attackSamples) env = (double) i / attackSamples;
					else if (i < startDecay + decaySamples) env = 1.0 - (double) (i - startDecay) / decaySamples;
					else env = 0.05;
					if (env < 0) env = 0;
					wetBuffer[i] *= env;
				}
				break;
				
				case FX_REAL_DRIVE:
				// 4. Real Drive (Усиление)
				double overallDrive = 1.0 + paramLevel / 50.0;
				for (int i = 0; i < wetBuffer.length; i++) {
					wetBuffer[i] *= overallDrive;
				}
				break;
				
				case FX_REAL_SATURATION:
				// 5. Real Saturation (Soft Clipping через Tanh)
				double satAmount = 1.0 + paramLevel / 20.0;
				for (int i = 0; i < wetBuffer.length; i++) {
					wetBuffer[i] = Math.tanh(wetBuffer[i] * satAmount);
				}
				break;
				
				case FX_REAL_BITCRUSH:
				// 6. Real Bitcrush (Квантование)
				int effectiveBitDepth = Math.max(1, 16 - paramLevel / 6);
				double maxQuantization = Math.pow(2, effectiveBitDepth) - 1;
				for (int i = 0; i < wetBuffer.length; i++) {
					double normalizedSample = wetBuffer[i];
					wetBuffer[i] = Math.round(normalizedSample * maxQuantization) / maxQuantization;
				}
				break;
			}
			
			// Смешивание и копирование обратно в основной буфер
			mixSignal(dryBuffer, wetBuffer, mixLevel);
			System.arraycopy(wetBuffer, 0, buffer, 0, buffer.length);
		}
		
		@Override
		protected Boolean doInBackground(String... params) {
			EffectEditorActivity activity = activityReference.get();
			if (activity == null || activity.isFinishing()) return false;
			
			String originalPath = params[0];
			File tempFile = null;
			
			try {
				// --- 1. Создание временного файла в КЕШЕ приложения ---
				// Генерируем уникальное имя файла в папке кеша (доступ гарантирован)
				String timestamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(new java.util.Date());
				tempFile = new File(activity.getCacheDir(), "applied_" + timestamp + ".wav");
				String tempPath = tempFile.getAbsolutePath();
				
				// Экранирование путей для shell
				String escapedOriginalPath = originalPath.replace("'", "'\\''");
				String escapedTempPath = tempPath.replace("'", "'\\''");
				
				// --- 2. Копирование оригинала в кеш (ИСПОЛЬЗУЯ ROOT) ---
				String copyCommand = "cp -f '" + escapedOriginalPath + "' '" + escapedTempPath + "' && chmod 666 '" + escapedTempPath + "'";
				String result = activity.executeRootCommand(copyCommand);
				if (result.startsWith("ERROR")) {
					Log.e(TAG, "Root copy command failed: " + result);
					return false;
				}
				
				// --- 3. Загрузка WAV из временного файла (теперь доступен Java IO) ---
				WavFile wav = WavFile.openWavFile(tempFile);
				int numFrames = (int) wav.getNumFrames();
				long sampleRate = wav.getSampleRate();
				int numChannels = wav.getNumChannels();
				int validBits = wav.getValidBits();
				
				double[] buffer = new double[numFrames * numChannels];
				wav.readFrames(buffer, numFrames);
				wav.close();
				
				// --- 4. Динамическая DSP-ЦЕПОЧКА ---
				for (int effectId : currentEffectOrder) {
					int paramLevel = currentParamLevels[effectId];
					int mixLevel = currentMixLevels[effectId];
					
					double[] dryBuffer = copyBuffer(buffer);
					
					applySingleEffect(buffer, dryBuffer, effectId, paramLevel, mixLevel, sampleRate);
				}
				
				// --- 5. Сохранение обработанного WAV обратно во временный файл ---
				WavFile outWav = WavFile.newWavFile(tempFile, numChannels, buffer.length, validBits, sampleRate);
				outWav.writeFrames(buffer, buffer.length);
				outWav.close();
				
				// --- 6. Перезапись оригинала обработанным файлом (ИСПОЛЬЗУЯ ROOT) ---
				// Перемещаем (заменяем) обработанный файл обратно на место оригинала
				String overwriteCommand = "mv -f '" + escapedTempPath + "' '" + escapedOriginalPath + "' && chmod 666 '" + escapedOriginalPath + "'";
				result = activity.executeRootCommand(overwriteCommand);
				if (result.startsWith("ERROR")) {
					Log.e(TAG, "Root overwrite command failed: " + result);
					return false;
				}
				
				return true;
				
				} catch (Exception e) {
				Log.e(TAG, "Error in final applying/overwriting: " + e.getMessage(), e);
				return false;
				} finally {
				// Удаляем временный файл, если он существует
				if (tempFile != null && tempFile.exists()) {
					if (!tempFile.delete()) {
						Log.w(TAG, "Failed to delete temporary file: " + tempFile.getName());
					}
				}
			}
		}
		
		@Override
		protected void onPostExecute(Boolean success) {
			EffectEditorActivity activity = activityReference.get();
			if (activity == null || activity.isFinishing()) return;
			
			activity.applyEffectsButton.setEnabled(true);
			activity.savePresetButton.setEnabled(true);
			activity.applyEffectsButton.setText("🔥 APPLY EFFECTS AND OVERWRITE FILE");
			
			if (success) {
				Toast.makeText(activity, "Effects applied successfully! File overwritten.", Toast.LENGTH_LONG).show();
				} else {
				Toast.makeText(activity, "Failed to apply effects and overwrite file. Check permissions/Root.", Toast.LENGTH_LONG).show();
			}
		}
		
		@Override
		protected void onCancelled() {
			super.onCancelled();
			EffectEditorActivity activity = activityReference.get();
			if (activity != null && !activity.isFinishing()) {
				activity.applyEffectsButton.setEnabled(true);
				activity.savePresetButton.setEnabled(true);
				activity.applyEffectsButton.setText("🔥 APPLY EFFECTS AND OVERWRITE FILE");
				Toast.makeText(activity, "File application cancelled.", Toast.LENGTH_SHORT).show();
			}
		}
	}
}